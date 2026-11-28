package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.theoriacodex.domain.model.SourceKey
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerGifCurrentPageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `only the current GIF page asks for its file in parts, not one swiping into view (ADR 0225)`() {
        val bytes = Random(7).nextBytes(GIF_BYTES).also { "GIF89a".toByteArray().copyInto(it) }
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path to exchange.requestHeaders.getFirst("Range")
            serve(exchange, bytes)
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        fun url(name: String) = "http://127.0.0.1:${server.address.port}/$name"
        try {
            compose.setContent {
                Column {
                    ViewerGifPlayer(SourceKey.PIXIV, listOf(url("current.gif")), Modifier.size(100.dp), controls = controls(isActive = true), events = EVENTS)
                    ViewerGifPlayer(SourceKey.PIXIV, listOf(url("incoming.gif")), Modifier.size(100.dp), controls = controls(isActive = false), events = EVENTS)
                }
            }
            compose.waitUntil(WAIT_MS) {
                requests.count { it.first == "/current.gif" } == 4 && requests.any { it.first == "/incoming.gif" }
            }

            assertEquals(3, requests.count { (path, range) -> path == "/current.gif" && range != null })
            assertEquals(listOf("/incoming.gif" to null), requests.filter { it.first == "/incoming.gif" })
        } finally {
            server.stop(0)
        }
    }

    private fun serve(exchange: HttpExchange, bytes: ByteArray) = exchange.use {
        val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
        exchange.responseHeaders.add("Content-Type", "image/gif")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        try {
            if (range == null) {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } else {
                val (start, end) = range
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                exchange.responseBody.write(bytes, start, end - start + 1)
            }
        } catch (_: IOException) {
        }
    }

    private fun controls(isActive: Boolean) = ViewerRendererControls(
        isActive = isActive, isPlaying = true, showControls = false, loopPlayback = true,
        playbackRate = 1f, restartRequest = 0L, loadGeneration = 0L,
    )

    private companion object {
        const val GIF_BYTES = 4 * 1024 * 1024
        const val WAIT_MS = 10_000L
        val EVENTS = ViewerRendererEvents.feedCard(onDurationKnown = {})
    }
}
