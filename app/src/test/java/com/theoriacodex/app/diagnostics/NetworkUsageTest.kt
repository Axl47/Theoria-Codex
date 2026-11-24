package com.theoriacodex.app.diagnostics

import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import coil.EventListener
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sun.net.httpserver.HttpServer
import com.theoriacodex.app.network.AppHttpClients
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkUsageTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, BODY_BYTES.toLong())
            exchange.responseBody.use { it.write(ByteArray(BODY_BYTES)) }
        }
        start()
    }

    @Before
    fun reset() = NetworkUsage.reset()

    @After
    fun stop() = server.stop(0)

    @Test
    fun `each client counts only its own traffic (ADR 0102)`() {
        val clients = AppHttpClients(CookieJar.NO_COOKIES)

        read(clients.images, times = 2)
        read(clients.video)
        read(clients.downloads, times = 3)
        read(clients.sources, times = 4)

        val stacks = NetworkUsage.snapshot().getJSONObject("stacks")
        mapOf("image" to 2, "video" to 1, "download" to 3, "source" to 4).forEach { (stack, requests) ->
            assertEquals(stack, requests, stacks.getJSONObject(stack).getInt("requests"))
            assertEquals(stack, requests * BODY_BYTES, stacks.getJSONObject(stack).getInt("bytes"))
        }
    }

    @Test
    fun `a redirect counts as its own request and a body counts as it arrived, before gzip decoding (ADR 0103)`() {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(BODY_BYTES)) } }.toByteArray()
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/landed")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/packed") { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, packed.size.toLong())
            exchange.responseBody.use { it.write(packed) }
        }
        val clients = AppHttpClients(CookieJar.NO_COOKIES)
        fun fetch(client: OkHttpClient, path: String) =
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}$path").build()).execute().use { it.body.bytes().size }

        assertEquals(BODY_BYTES, fetch(clients.images, "/moved"))
        assertEquals(BODY_BYTES, fetch(clients.downloads, "/packed"))

        val stacks = NetworkUsage.snapshot().getJSONObject("stacks")
        assertEquals(2, stacks.getJSONObject("image").getInt("requests"))
        assertEquals(BODY_BYTES, stacks.getJSONObject("image").getInt("bytes"))
        assertEquals(packed.size, stacks.getJSONObject("download").getInt("bytes"))
    }

    @Test
    fun `a download abandoned midway counts only what was read`() {
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/a.jpg").build()
        AppHttpClients(CookieJar.NO_COOKIES).images.newCall(request).execute().use { response ->
            response.body.source().readByteArray(PARTIAL_BYTES.toLong())
        }

        val image = NetworkUsage.snapshot().getJSONObject("stacks").getJSONObject("image")
        assertEquals(1, image.getInt("requests"))
        assertTrue(image.getInt("bytes") in PARTIAL_BYTES until BODY_BYTES / 2)
    }

    @Test
    fun `image results count by source and still reach the wrapped listener`() {
        val seen = mutableListOf<String>()
        val listener = NetworkUsage.countingImageResults(object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { seen += "success" }
            override fun onError(request: ImageRequest, result: ErrorResult) { seen += "error" }
        })
        val request = ImageRequest.Builder(ApplicationProvider.getApplicationContext()).data("a").build()

        listener.onSuccess(request, SuccessResult(ColorDrawable(), request, DataSource.DISK))
        listener.onSuccess(request, SuccessResult(ColorDrawable(), request, DataSource.NETWORK))
        listener.onError(request, ErrorResult(null, request, IOException()))
        NetworkUsage.videoCacheListener.onCachedBytesRead(0, 7)

        val snapshot = NetworkUsage.snapshot()
        assertEquals(listOf("success", "success", "error"), seen)
        val sources = snapshot.getJSONObject("imageResultsBySource")
        assertEquals(listOf(0, 1, 1, 0), listOf("memory_cache", "disk", "network", "memory").map(sources::getInt))
        assertEquals(7, snapshot.getInt("videoCachedBytesRead"))
    }

    private fun read(client: OkHttpClient, times: Int = 1) = repeat(times) { index ->
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/$index").build()
        client.newCall(request).execute().use { it.body.bytes() }
    }

    private companion object {
        const val BODY_BYTES = 1_000_000
        const val PARTIAL_BYTES = 100
    }
}
