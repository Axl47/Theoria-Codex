package com.theoriacodex.app.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.theoriacodex.domain.model.SourceKey
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerGifTransportTest {
    @Test
    fun `remote bytes are fetched once with source headers and reused by fallback`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val calls = AtomicInteger()
        var referer: String? = null
        val bytes = "GIF89a fixture bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.gif") { exchange ->
            calls.incrementAndGet()
            referer = exchange.requestHeaders.getFirst("Referer")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/media.gif"
        try {
            assertArrayEquals(bytes, loadRemoteViewerGifBytes(context, SourceKey.PIXIV, url))
            assertArrayEquals(bytes, loadRemoteViewerGifBytes(context, SourceKey.PIXIV, url))
            assertEquals(1, calls.get())
            assertEquals("https://www.pixiv.net/", referer)
            val cached = cachedViewerGifLocation(context, SourceKey.PIXIV, url)
            assertArrayEquals(bytes, java.io.File(cached).readBytes())
        } finally { server.stop(0) }
    }

    @Test
    fun `leaving viewer cancels a stalled response promptly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stalled.gif") { exchange ->
            exchange.sendResponseHeaders(200, 100L)
            exchange.responseBody.write(1)
            exchange.responseBody.flush()
            started.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val request = async {
                loadRemoteViewerGifBytes(context, SourceKey.PIXIV, "http://127.0.0.1:${server.address.port}/stalled.gif")
            }
            withTimeout(2_000L) { started.await(); request.cancelAndJoin() }
        } finally { release.countDown(); server.stop(0) }
    }
}
