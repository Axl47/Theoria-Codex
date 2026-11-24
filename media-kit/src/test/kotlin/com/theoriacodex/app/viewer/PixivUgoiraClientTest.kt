package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.TimeUnit
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
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.PixivCredentialSession
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
@Config(application = Application::class)
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
            client.load("42", UgoiraSizeBucket.VIEWER)
        } catch (error: CancellationException) {
            thrown = error
        }

        assertNotNull(thrown)
        assertEquals(expected.message, thrown?.message)
        assertNull(client.cached("42", UgoiraSizeBucket.VIEWER))
    }

    @Test
    fun `concurrent consumers share one metadata and archive acquisition (ADR 0112)`() = runTest {
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
    fun `partial frames reach both consumers and cancelling one preserves the other (ADR 0112)`() = runTest {
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
    fun `last departing consumer interrupts archive acquisition and a returning card retries (ADR 0112)`() = runTest {
        val started = CompletableDeferred<Unit>()
        val interrupted = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = fixtureClient(timeoutMs = 600_000L, download = { _, _, destination ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                try {
                    CountDownLatch(1).await()
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
            }
            writeFrameZip(destination)
            BinaryResponse(200)
        })
        val first = async { client.load("returning", UgoiraSizeBucket.CARD) }
        started.await()
        first.cancelAndJoin()
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
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
    fun `warming an archive lets a later load play from disk without another download`() = runTest {
        val downloads = AtomicInteger()
        val client = fixtureClient(download = { _, _, file ->
            downloads.incrementAndGet()
            writeFrameZip(file)
            BinaryResponse(200)
        })

        assertTrue(client.warmArchive("warm"))
        assertTrue(client.load("warm", UgoiraSizeBucket.CARD).isSuccess)

        assertEquals(1, downloads.get())
    }

    @Test
    fun `sampling frames leaves the playback store alone, a refused post samples nothing, and a refused sign-in waits (ADR 0107)`() = runTest {
        val budget = NetworkBudget(maxBytes = 1L shl 20)
        val client = fixtureClient()

        assertTrue(client.sampleFrames("sampled", count = 6, sizePx = 2, budget) is FrameSample.Frames)
        assertEquals(0L, client.disposableCacheBytes())

        val refused = fixtureClient(download = { _, _, _ -> BinaryResponse(404) })
        assertEquals(FrameSample.Refused, refused.sampleFrames("gone", count = 6, sizePx = 2, budget))

        val forbidden = fixtureClient(
            credentials = SavingCredentialsProvider(),
            httpClient = RefreshingHttpClient,
            download = { _, _, _ -> BinaryResponse(403) },
        )
        assertEquals(FrameSample.Unreachable, forbidden.sampleFrames("locked", count = 6, sizePx = 2, budget))

        assertEquals(FrameSample.Unreadable, fixtureClient().sampleFrames("large", count = 6, sizePx = 2, NetworkBudget(maxBytes = 16)))
    }

    @Test
    fun `sampling downloads the 600 px archive into a file it deletes and reads a held archive without marking it used (ADR 0107)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val downloads = mutableListOf<Pair<String, File>>()
        fun client() = PixivUgoiraClient(
            credentialsProvider = FixedCredentialsProvider,
            httpClient = UnusedHttpClient,
            archiveDirectory = directory,
            metadataFetcher = { _, _ ->
                TextResponse(200, """{"ugoira_metadata":{"zip_urls":{"original":"https://example.test/big.zip","medium":"https://example.test/600.zip"},"frames":[{"file":"0001.png","delay":50}]}}""")
            },
            zipDownloader = { url, _, file ->
                downloads += url to file
                writeFrameZip(file)
                BinaryResponse(200)
            },
        )
        val budget = NetworkBudget(maxBytes = 1L shl 20)

        assertTrue(client().sampleFrames("fresh", count = 2, sizePx = 2, budget) is FrameSample.Frames)
        assertEquals(listOf("https://example.test/600.zip"), downloads.map { it.first })
        assertFalse(downloads.single().second.exists())

        assertTrue(client().warmArchive("held"))
        val held = directory.listFiles().orEmpty().single { it.extension == "zip" }
        val lastUsed = held.lastModified()
        Thread.sleep(20)
        downloads.clear()

        assertTrue(client().sampleFrames("held", count = 2, sizePx = 2, budget) is FrameSample.Frames)
        assertEquals(emptyList<Pair<String, File>>(), downloads)
        assertEquals(lastUsed, held.lastModified())
    }

    @Test
    fun `a failed warm reports false and leaves playback able to retry`() = runTest {
        val downloads = AtomicInteger()
        val client = fixtureClient(download = { _, _, file ->
            if (downloads.incrementAndGet() == 1) BinaryResponse(404) else {
                writeFrameZip(file)
                BinaryResponse(200)
            }
        })

        assertFalse(client.warmArchive("flaky"))
        assertTrue(client.load("flaky", UgoiraSizeBucket.CARD).isSuccess)
    }

    @Test
    fun `memory pressure drops decoded frames and playback recovers from the disk archive (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder("pressure")
        val client = fixtureClient(directory)
        assertTrue(client.load("disk", UgoiraSizeBucket.CARD).isSuccess)
        assertTrue(client.cachedDecodedBytes() > 0L)

        client.releaseMemoryCaches()

        assertEquals(0L, client.cachedDecodedBytes())
        assertEquals(null, client.cached("disk", UgoiraSizeBucket.CARD))
        assertTrue(client.load("disk", UgoiraSizeBucket.CARD).isSuccess)
    }

    @Test
    fun `deadline terminates partial decoding without caching or recycling displayed frames (ADR 0112)`() = runTest {
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
    fun `a malformed frame entry is skipped and the rest still play (ADR 0112)`() = runTest {
        val client = fixtureClient(
            frames = """["junk",{"file":{"bad":true}},{"file":"0001.png","delay":50}]""",
        )

        assertTrue(client.load("skip", UgoiraSizeBucket.CARD).isSuccess)
    }

    @Test
    fun `transient metadata failures stop after three attempts (ADR 0112)`() = runTest {
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

    @Test
    fun `clearing the disposable cache while an archive downloads keeps it and reports false (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val written = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = fixtureClient(directory, download = { _, _, file ->
            writeFrameZip(file)
            written.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            BinaryResponse(200)
        })
        val warming = async { client.warmArchive("busy") }
        written.await()

        assertFalse(client.clearDisposableCache())
        assertTrue(File(directory, "busy.zip").isFile)

        release.countDown()
        assertTrue(warming.await())
        assertTrue(client.clearDisposableCache())
        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test
    fun `an archive that fails validation leaves neither the archive nor its timing (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val client = fixtureClient(directory, frames = """[{"file":"0001.png","delay":50},{"file":"0002.png","delay":50}]""")

        assertTrue(client.load("partial", UgoiraSizeBucket.CARD).isFailure)

        assertEquals(emptyList<String>(), directory.list().orEmpty().toList())
    }

    @Test
    fun `a cached archive that fails to decode loses its timing, so the next load validates it again (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val broken = fixtureClient(directory, decode = { _, _, _, _ -> throw IOException("damaged frame") })

        assertTrue(broken.load("damaged", UgoiraSizeBucket.CARD).isFailure)
        assertFalse(File(directory, "damaged.zip.json").exists())

        val reopened = fixtureClient(directory, download = { _, _, _ -> error("a valid archive is reused") })
        assertTrue(reopened.load("damaged", UgoiraSizeBucket.CARD).isSuccess)
        assertTrue(File(directory, "damaged.zip.json").exists())
    }

    @Test
    fun `a cached archive that no longer validates is downloaded again (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val downloads = AtomicInteger()
        val download: (String, String, File) -> BinaryResponse = { _, _, file ->
            downloads.incrementAndGet()
            writeFrameZip(file)
            BinaryResponse(200)
        }
        assertTrue(fixtureClient(directory, decode = { _, _, _, _ -> throw IOException("damaged frame") }, download = download)
            .load("damaged", UgoiraSizeBucket.CARD).isFailure)
        File(directory, "damaged.zip").writeBytes(ByteArray(64) { 7 })

        assertTrue(fixtureClient(directory, download = download).load("damaged", UgoiraSizeBucket.CARD).isSuccess)
        assertEquals(2, downloads.get())
    }

    @Test
    fun `an offline archive that fails to decode keeps its timing (ADR 0112)`() = runTest {
        val offline = File(temporaryFolder.newFolder("offline"), "animation.zip")
        fixtureClient().copyArchiveForOffline("pinned", offline)
        val reopened = PixivUgoiraClient(
            credentialsProvider = CancellingCredentialsProvider(CancellationException("Network was reached")),
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder("empty-cache"),
            offlineArchiveLookup = { offline },
            decode = { _, _, _, _ -> throw IOException("damaged frame") },
        )

        assertTrue(reopened.load("pinned", UgoiraSizeBucket.CARD).isFailure)

        assertTrue(offline.isFile)
        assertNotNull(readUgoiraFrameMetadata(offline))
    }

    @Test
    fun `an archive over the compressed limit is not downloaded again (ADR 0112)`() = runTest {
        val downloads = AtomicInteger()
        val client = fixtureClient(download = { _, _, _ ->
            downloads.incrementAndGet()
            throw UgoiraArchiveTooLarge()
        })

        assertTrue(client.load("huge", UgoiraSizeBucket.CARD).isFailure)
        assertEquals(1, downloads.get())
        assertEquals(FrameSample.Unreadable, client.sampleFrames("huge", count = 2, sizePx = 2, NetworkBudget(maxBytes = 1L shl 20)))
        assertEquals(2, downloads.get())
    }

    @Test
    fun `metadata and archive requests retry an I-O failure, 429 and 5xx with growing waits, then stop after three tries (ADR 0112)`() = runTest {
        val metadataAt = mutableListOf<Long>()
        val zipAt = mutableListOf<Long>()
        val metadataAnswers = ArrayDeque(listOf<() -> TextResponse>({ throw IOException("reset") }, { TextResponse(503, "") }))
        val zipAnswers = ArrayDeque(listOf<() -> BinaryResponse>({ BinaryResponse(429) }, { throw IOException("reset") }))
        val client = PixivUgoiraClient(
            credentialsProvider = FixedCredentialsProvider,
            httpClient = UnusedHttpClient,
            archiveDirectory = temporaryFolder.newFolder(),
            metadataFetcher = { _, _ ->
                metadataAt += System.nanoTime()
                metadataAnswers.removeFirstOrNull()?.invoke()
                    ?: TextResponse(200, """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":[{"file":"0001.png","delay":50}]}}""")
            },
            zipDownloader = { _, _, file ->
                zipAt += System.nanoTime()
                zipAnswers.removeFirstOrNull()?.invoke() ?: run {
                    writeFrameZip(file)
                    BinaryResponse(200)
                }
            },
        )

        assertTrue(client.load("retried", UgoiraSizeBucket.CARD).isSuccess)
        listOf(metadataAt, zipAt).forEach { times ->
            assertEquals(3, times.size)
            val waits = times.zipWithNext { earlier, later -> (later - earlier) / 1_000_000 }
            assertTrue(waits.toString(), waits[0] >= 300 && waits[1] > waits[0])
        }

        val zipCalls = AtomicInteger()
        val failing = fixtureClient(download = { _, _, _ -> zipCalls.incrementAndGet(); BinaryResponse(500) })
        assertTrue(failing.load("down", UgoiraSizeBucket.CARD).isFailure)
        assertEquals(3, zipCalls.get())
    }

    @Test
    fun `the first 401 or 403 refreshes the token and retries with it, and a second one fails (ADR 0112)`() = runTest {
        for (status in listOf(401, 403)) {
            val tokens = mutableListOf<String>()
            val recovering = fixtureClient(credentials = SavingCredentialsProvider(), httpClient = RefreshingHttpClient, download = { _, token, file ->
                tokens += token
                if (tokens.size == 1) BinaryResponse(status) else {
                    writeFrameZip(file)
                    BinaryResponse(200)
                }
            })
            assertTrue(recovering.load("auth-$status", UgoiraSizeBucket.CARD).isSuccess)
            assertEquals(listOf("access", "fresh"), tokens)

            val refused = AtomicInteger()
            val signedOut = fixtureClient(credentials = SavingCredentialsProvider(), httpClient = RefreshingHttpClient, download = { _, _, _ ->
                refused.incrementAndGet()
                BinaryResponse(status)
            })
            assertTrue(signedOut.load("refused-$status", UgoiraSizeBucket.CARD).isFailure)
            assertEquals(2, refused.get())
        }
    }

    @Test
    fun `clearing the disposable cache while playback decodes keeps the archive and reports false (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        val decoding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = fixtureClient(directory, decode = { file, frames, size, publish ->
            decoding.complete(Unit)
            release.await()
            decodeUgoiraFrames(file, frames, size, publish)
        })
        val playing = async { client.load("playing", UgoiraSizeBucket.CARD) }
        decoding.await()

        assertFalse(client.clearDisposableCache())
        assertTrue(File(directory, "playing.zip").isFile)

        release.complete(Unit)
        assertTrue(playing.await().isSuccess)
    }

    @Test
    fun `pruning the archive store leaves an archive still being fetched (ADR 0112)`() = runTest {
        val directory = temporaryFolder.newFolder()
        repeat(130) { index -> File(directory, "stale-$index.zip").apply { writeBytes(byteArrayOf(1)); setLastModified(2_000L + index) } }
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = fixtureClient(directory, download = { _, _, file ->
            writeFrameZip(file)
            if (file.name == "busy.zip") {
                file.setLastModified(1_000L)
                fetching.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            BinaryResponse(200)
        })
        val busy = async { client.warmArchive("busy") }
        runCurrent()
        assertTrue(fetching.await(5, TimeUnit.SECONDS))

        assertTrue(client.warmArchive("fresh"))
        assertTrue(File(directory, "busy.zip").isFile)
        assertFalse(File(directory, "stale-0.zip").exists())

        release.countDown()
        assertTrue(busy.await())
    }

    @Test
    fun `no more than three archives download at once (ADR 0112)`() = runTest {
        val downloading = AtomicInteger()
        val most = AtomicInteger()
        val release = CountDownLatch(1)
        val client = fixtureClient(download = { _, _, file ->
            most.accumulateAndGet(downloading.incrementAndGet(), ::maxOf)
            release.await(5, TimeUnit.SECONDS)
            writeFrameZip(file)
            downloading.decrementAndGet()
            BinaryResponse(200)
        })
        val warming = List(4) { index -> async { client.warmArchive("slot-$index") } }
        runCurrent()
        val deadline = System.currentTimeMillis() + 5_000
        while (downloading.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(300)

        assertEquals(3, downloading.get())
        release.countDown()
        assertTrue(warming.awaitAll().all { it })
        assertEquals(3, most.get())
    }

    @Test
    fun `every try at one post's archive spends the same sampling budget (ADR 0107)`() = runTest {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a.zip") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(ByteArray(40))
                exchange.responseBody.flush()
                exchange.close()
            }
            start()
        }
        try {
            val client = PixivUgoiraClient(
                credentialsProvider = FixedCredentialsProvider,
                httpClient = UnusedHttpClient,
                archiveDirectory = temporaryFolder.newFolder(),
                metadataFetcher = { _, _ ->
                    TextResponse(200, """{"ugoira_metadata":{"zip_urls":{"medium":"http://127.0.0.1:${server.address.port}/a.zip"},"frames":[{"file":"0001.png","delay":50}]}}""")
                },
            )

            assertEquals(FrameSample.Unreadable, client.sampleFrames("budgeted", count = 2, sizePx = 2, NetworkBudget(maxBytes = 60)))
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun fixtureClient(
        directory: File = temporaryFolder.newFolder(),
        timeoutMs: Long = 30_000L,
        decode: UgoiraFrameDecoder = ::decodeUgoiraFrames,
        credentials: SourceCredentialsProvider = FixedCredentialsProvider,
        httpClient: SourceHttpClient = UnusedHttpClient,
        frames: String = """[{"file":"0001.png","delay":50}]""",
        download: (String, String, File) -> BinaryResponse = { _, _, file ->
            writeFrameZip(file)
            BinaryResponse(200)
        },
    ) = PixivUgoiraClient(
        credentialsProvider = credentials,
        httpClient = httpClient,
        archiveDirectory = directory,
        metadataFetcher = { _, _ -> TextResponse(200,
            """{"ugoira_metadata":{"zip_urls":{"medium":"https://example.test/a.zip"},"frames":$frames}}""",
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
    }

    private object FixedCredentialsProvider : SourceCredentialsProvider {
        fun getTokens() = PixivAuthTokens(accessToken = "access", refreshToken = "refresh", expiresAtEpochMs = Long.MAX_VALUE)
        override suspend fun getPixivTokens(): PixivAuthTokens = getTokens()
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) = Unit
        override suspend fun clearPixivTokens() = Unit
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

    private class SavingCredentialsProvider : SourceCredentialsProvider {
        private var tokens: PixivAuthTokens? = FixedCredentialsProvider.getTokens()
        override suspend fun getPixivTokens(): PixivAuthTokens? = tokens
        override suspend fun savePixivTokens(tokens: PixivAuthTokens) {
            this.tokens = tokens
        }
        override suspend fun clearPixivTokens() {
            tokens = null
        }
        override suspend fun commitPixivRefresh(session: PixivCredentialSession, tokens: PixivAuthTokens?): Boolean {
            this.tokens = tokens
            return true
        }
    }

    private object RefreshingHttpClient : SourceHttpClient by UnusedHttpClient {
        override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
            SourceHttpResponse(200, """{"access_token":"fresh","refresh_token":"refresh","expires_in":3600}""")
    }
}
