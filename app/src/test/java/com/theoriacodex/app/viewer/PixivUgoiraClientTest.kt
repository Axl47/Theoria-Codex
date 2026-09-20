package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PixivUgoiraClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `load propagates credential cancellation without caching a partial playback`() = runTest {
        val expected = CancellationException("viewer left")
        val client = PixivUgoiraClient(
            credentialsProvider = CancellingCredentialsProvider(expected),
            httpClient = UnusedHttpClient,
        )

        var thrown: CancellationException? = null
        try {
            client.load("42")
        } catch (error: CancellationException) {
            thrown = error
        }

        assertNotNull(thrown)
        assertEquals(expected.message, thrown?.message)
        assertNull(client.cached("42"))
    }

    @Test
    fun `concurrent consumers share one metadata and archive acquisition`() = runTest {
        val metadataCalls = AtomicInteger()
        val archiveCalls = AtomicInteger()
        val metadataGate = CountDownLatch(1)
        val client = PixivUgoiraClient(
            credentialsProvider = FixedCredentialsProvider,
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder("archives"),
            decode = { _, _, _, _ -> throw java.io.IOException("Undecodable fixture") },
            metadataFetcher = { _, _ ->
                metadataCalls.incrementAndGet()
                metadataGate.await()
                TextResponse(
                    200,
                    """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":[{"file":"0001.jpg","delay":50}]}}""",
                )
            },
            zipDownloader = { _, _, destination ->
                archiveCalls.incrementAndGet()
                ZipOutputStream(FileOutputStream(destination)).use { zip ->
                    zip.putNextEntry(ZipEntry("0001.jpg"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
                BinaryResponse(200)
            },
        )

        val consumers = List(8) { async { client.load("shared", UgoiraSizeBucket.CARD) } }
        runCurrent()
        metadataGate.countDown()
        val results = consumers.awaitAll()

        assertEquals(1, metadataCalls.get())
        assertEquals(1, archiveCalls.get())
        assertTrue(results.all { result -> result.isFailure })
    }

    @Test
    fun `partial frames reach both consumers and cancelling one preserves the other`() = runTest {
        val release = CompletableDeferred<Unit>()
        val firstPartial = CompletableDeferred<Unit>()
        val secondPartial = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val client = fixtureClient(decode = { _, _, _, publish ->
            calls.incrementAndGet()
            val firstFrame = UgoiraFrame(bitmap, 50)
            publish(UgoiraPlayback(listOf(firstFrame), listOf(50, 50)))
            release.await()
            UgoiraPlayback(listOf(firstFrame, firstFrame))
        })
        val first = async {
            client.observeLoad("partial", UgoiraSizeBucket.CARD).onEach { result ->
                if (!result.getOrThrow().isComplete) firstPartial.complete(Unit)
            }.last()
        }
        val second = async {
            client.observeLoad("partial", UgoiraSizeBucket.CARD).onEach { result ->
                if (!result.getOrThrow().isComplete) secondPartial.complete(Unit)
            }.last()
        }
        try {
            firstPartial.await()
            secondPartial.await()
            assertNull(client.cached("partial", UgoiraSizeBucket.CARD))
            first.cancelAndJoin()
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertTrue(second.await().getOrThrow().isComplete)
            assertEquals(1, calls.get())
            assertFalse(bitmap.isRecycled)
            assertNotNull(client.cached("partial", UgoiraSizeBucket.CARD))
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun `last departing consumer interrupts archive acquisition and a returning card retries`() = runTest {
        val started = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = fixtureClient(download = { _, _, destination ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try {
                    CountDownLatch(1).await()
                } catch (error: InterruptedException) {
                    interrupted.complete(Unit)
                    throw error
                }
            }
            writeFrameZip(destination)
            BinaryResponse(200)
        })
        val first = async { client.load("returning", UgoiraSizeBucket.CARD) }
        started.await()
        first.cancelAndJoin()
        // IO work has its own dispatcher; wait for actual cancellation, not just the caller's completion.
        interrupted.await()
        assertTrue(client.load("returning", UgoiraSizeBucket.CARD).isSuccess)
        assertEquals(2, calls.get())
    }

    @Test
    fun `fresh client reuses cached ZIP and timings without credentials or network`() = runTest {
        val directory = temporaryFolder.newFolder("persistent")
        val first = fixtureClient(directory)
        assertTrue(first.load("disk", UgoiraSizeBucket.CARD).isSuccess)
        val reopened = PixivUgoiraClient(
            credentialsProvider = CancellingCredentialsProvider(CancellationException("network was reached")),
            httpClient = UnusedHttpClient,
            archiveDirectory = directory,
            metadataFetcher = { _, _ -> error("Disk cache must not fetch metadata") },
            zipDownloader = { _, _, _ -> error("Disk cache must not download") },
        )
        assertTrue(reopened.load("disk", UgoiraSizeBucket.CARD).isSuccess)
    }

    @Test
    fun `deadline terminates partial decoding without caching or recycling displayed frames`() = runTest {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val partial = CompletableDeferred<Unit>()
        val client = fixtureClient(timeoutMs = 1_000L, decode = { _, _, _, publish ->
            publish(UgoiraPlayback(listOf(UgoiraFrame(bitmap, 50)), listOf(50, 50)))
            partial.complete(Unit)
            awaitCancellation()
        })
        val loading = async { client.load("timeout", UgoiraSizeBucket.CARD) }
        partial.await()
        val result = loading.await()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("timed out"))
        assertNull(client.cached("timeout", UgoiraSizeBucket.CARD))
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun `transient metadata failures stop after three attempts`() = runTest {
        val calls = AtomicInteger()
        val client = PixivUgoiraClient(
            credentialsProvider = FixedCredentialsProvider,
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder("retry"),
            metadataFetcher = { _, _ -> calls.incrementAndGet(); TextResponse(503, "") },
        )
        assertTrue(client.load("retry", UgoiraSizeBucket.CARD).isFailure)
        assertEquals(3, calls.get())
    }

    @Test
    fun `offline archive survives disposable clearing and works without credentials`() = runTest {
        val original = fixtureClient()
        val offline = File(temporaryFolder.newFolder("offline"), "animation.zip")
        original.copyArchiveForOffline("pinned", offline)
        assertNotNull(readUgoiraFrameMetadata(offline))
        assertTrue(original.clearDisposableCache())
        val reopened = PixivUgoiraClient(
            credentialsProvider = CancellingCredentialsProvider(CancellationException("Network was reached")),
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder("empty-cache"),
            offlineArchiveLookup = { offline },
        )
        assertTrue(reopened.load("pinned", UgoiraSizeBucket.CARD).isSuccess)
        assertTrue(reopened.clearDisposableCache())
        assertTrue(offline.isFile)
        assertEquals(0L, reopened.disposableCacheBytes())
    }

    private fun fixtureClient(
        directory: File = temporaryFolder.newFolder(),
        timeoutMs: Long = 30_000L,
        decode: UgoiraFrameDecoder = ::decodeUgoiraFrames,
        download: (String, String, File) -> BinaryResponse = { _, _, file ->
            writeFrameZip(file)
            BinaryResponse(200)
        },
    ) = PixivUgoiraClient(
        credentialsProvider = FixedCredentialsProvider,
        httpClient = UnusedHttpClient,
        archiveDirectory = directory,
        metadataFetcher = { _, _ -> TextResponse(200,
            """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":[{"file":"0001.png","delay":50}]}}""",
        ) },
        zipDownloader = download,
        decode = decode,
        operationTimeoutMs = timeoutMs,
    )

    private fun writeFrameZip(destination: File) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry("0001.png"))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    private class CancellingCredentialsProvider(
        private val cancellation: CancellationException,
    ) : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens? = throw cancellation
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private object FixedCredentialsProvider : SourceCredentialsProvider {
        override suspend fun getPixivTokens(): PixivAuthTokens = PixivAuthTokens(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
        override suspend fun getGelbooruCredentials(): GelbooruCredentials? = null
        override suspend fun saveGelbooruCredentials(credentials: GelbooruCredentials) = Unit
        override suspend fun clearGelbooruCredentials() = Unit
        override suspend fun getRule34XxxCredentials(): Rule34XxxCredentials? = null
        override suspend fun saveRule34XxxCredentials(credentials: Rule34XxxCredentials) = Unit
        override suspend fun clearRule34XxxCredentials() = Unit
    }

    private object UnusedHttpClient : SourceHttpClient {
        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("HTTP should not be reached after credential cancellation")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("HTTP should not be reached after credential cancellation")
    }
}
