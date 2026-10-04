package com.theoriacodex.app.update

import android.content.ContextWrapper
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `bounded downloads promote atomically and leave an older APK intact on truncation or overflow`() = runBlocking {
        val context = fileContext(temporary.root)
        val connection = StubConnection(ByteArrayInputStream("good".toByteArray()), 4)
        val manager = ApkDownloadManager(context, "app.apk", { connection }, maximumBytes = 8)
        assertEquals("good", manager.download(remote(4)) {}.getOrThrow().readText())
        for ((body, expected) in listOf("short" to 6L, "too large" to null)) {
            val failing = ApkDownloadManager(context, "app.apk", { StubConnection(body.byteInputStream()) }, maximumBytes = 8)
            assertTrue(failing.download(remote(expected)) {}.isFailure)
            assertEquals("good", manager.outputFile().readText())
            assertEquals(listOf("app.apk"), manager.outputFile().parentFile!!.list()!!.toList())
        }
    }

    @Test fun `the overall download deadline cleans incomplete staging and preserves an older APK`() = runBlocking {
        val directory = temporary.root.resolve("theoria_codex/updates").apply { mkdirs() }
        directory.resolve("update-abandoned.part").writeText("interrupted old process")
        directory.resolve("app.apk").writeText("previous")
        val stalled = object : InputStream() {
            override fun read(): Int { Thread.sleep(5_000); return -1 }
        }
        val manager = ApkDownloadManager(fileContext(temporary.root), "app.apk", { StubConnection(stalled) }, timeoutMs = 50)
        val result = withTimeout(1_000) { manager.download(remote(null)) {} }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("timed out") == true)
        assertEquals("previous", manager.outputFile().readText())
        assertFalse(directory.resolve("update-abandoned.part").exists())
    }

    @Test fun `cancelled late reads cannot publish over a retry or keep its staging file`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldFinished = CountDownLatch(1)
        val oldStream = object : InputStream() {
            private var readOnce = false
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (readOnce) return -1
                started.countDown()
                while (true) {
                    try { release.await(); break } catch (_: InterruptedException) { /* Simulate a non-interruptible platform read. */ }
                }
                readOnce = true
                "old".toByteArray().copyInto(buffer, offset)
                return 3
            }
            override fun close() { oldFinished.countDown() }
        }
        val attempts = AtomicInteger()
        val manager = ApkDownloadManager(fileContext(temporary.root), "app.apk", {
            if (attempts.getAndIncrement() == 0) StubConnection(oldStream) else StubConnection("new".byteInputStream())
        })
        val old = async { manager.download(remote(null)) {} }
        try {
            kotlinx.coroutines.yield()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(1_000) { old.cancelAndJoin() }
            assertEquals("new", manager.download(remote(null)) {}.getOrThrow().readText())
        } finally { release.countDown() }
        assertTrue(oldFinished.await(3, TimeUnit.SECONDS))
        assertEquals("new", manager.outputFile().readText())
        withTimeout(1_000) {
            while (manager.outputFile().parentFile!!.list()!!.any { it.endsWith(".part") }) kotlinx.coroutines.delay(10)
        }
    }

    @Test fun `latest and history share one bounded cancellable release request with expiry`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        var now = 0L
        val http = object : SourceHttpClient {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>) = error("bounded bytes expected")
            override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) = error("unexpected POST")
            override suspend fun getBytes(url: String, query: Map<String, String>, headers: Map<String, String>, range: SourceByteRange?, maxBodyBytes: Int): SourceByteResponse {
                calls.incrementAndGet()
                assertEquals(4 * 1024 * 1024, maxBodyBytes)
                release.await()
                return SourceByteResponse(200, "[]".toByteArray())
            }
        }
        val client = GitHubReleaseFeedClient("owner", "repo", "main", "app.apk", http) { now }
        val latest = async { client.latestMainPrerelease() }
        val history = async { client.mainPrereleaseHistory(50) }
        release.complete(Unit)
        assertTrue(latest.await().isSuccess)
        assertTrue(history.await().isSuccess)
        assertEquals(1, calls.get())
        now = 300_001L
        assertTrue(client.latestMainPrerelease().isSuccess)
        assertEquals(2, calls.get())
    }

    private fun fileContext(directory: File) = object : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
    }

    private fun remote(size: Long?) = RemoteUpdate(1, "v0.1.1", 11, "abcdef0", "https://example.test/app.apk", size)

    private class StubConnection(private val stream: InputStream, private val length: Long = -1) : HttpURLConnection(URL("https://example.test")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = stream
    }
}
