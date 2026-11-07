@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.net.Uri
import androidx.media3.datasource.DataSpec
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoHttpDataSourceTest {
    private val origin = StalledOrigin()

    @After fun stopOrigin() = origin.close()

    @Test fun `closing a half-read response drops its connection without waiting to drain it`() {
        val source = AbandoningHttpDataSourceFactory(videoHttpClient()).createDataSource()
        source.open(DataSpec(Uri.parse(origin.url)))
        source.read(ByteArray(SENT_BYTES), 0, SENT_BYTES)

        // The origin sends nothing more, so a draining close would wait out its 100 ms timeout.
        val startedAt = System.nanoTime()
        source.close()
        val closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("close took $closeMs ms", closeMs < DRAIN_TIMEOUT_MS / 2)
        assertTrue("the origin saw its connection end", origin.disconnected.await(1, TimeUnit.SECONDS))
    }

    @Test fun `requests send the video agent once, with the stream's other headers`() {
        val source = AbandoningHttpDataSourceFactory(videoHttpClient()).createDataSource()
        val spec = DataSpec.Builder().setUri(origin.url)
            .setHttpRequestHeaders(mapOf("Referer" to "https://example.org/", "User-Agent" to "TheoriaSource/1"))
            .build()
        source.open(spec)
        source.close()

        val headers = origin.requestHeaders.single()
        assertEquals(listOf("Mozilla/5.0"), headers.valuesOf("user-agent"))
        assertEquals(listOf("https://example.org/"), headers.valuesOf("referer"))
        assertEquals(listOf("identity"), headers.valuesOf("accept-encoding"))
    }

    private fun List<Pair<String, String>>.valuesOf(name: String) = filter { it.first == name }.map { it.second }
}

private const val SENT_BYTES = 1024
private const val DRAIN_TIMEOUT_MS = 100L

/** An HTTP/1.1 origin that promises a large body, sends its first [SENT_BYTES], then stalls until disconnected. */
private class StalledOrigin : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/video.mp4"
    val requestHeaders = CopyOnWriteArrayList<List<Pair<String, String>>>()
    val disconnected = CountDownLatch(1)

    private val worker = thread(isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                val headers = generateSequence { input.readLine() }.drop(1).takeWhile { it.isNotEmpty() }
                    .map { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
                    .toList()
                requestHeaders += headers
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${1 shl 20}\r\n\r\n".toByteArray())
                output.write(ByteArray(SENT_BYTES))
                output.flush()
                // A closed connection reads as end of stream (or a reset) here.
                runCatching { while (socket.getInputStream().read() >= 0) Unit }
            }
        }
        disconnected.countDown()
    }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}
