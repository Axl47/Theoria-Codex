package com.theoriacodex.app.viewer

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
