package com.theoriacodex.app.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppHttpClientsTest {
    private val agents = CopyOnWriteArrayList<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }

    @After
    fun stop() = server.stop(0)

    private fun get(client: OkHttpClient, path: String) {
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/$path").header("User-Agent", "catalog").build()
        client.newCall(request).execute().close()
    }

    @Test
    fun `every client sends a verified site's browser agent and leaves other sites alone`() {
        val clients = AppHttpClients(CookieJar.NO_COOKIES, verifiedUserAgent = { url -> "browser".takeIf { "verified" in url } })

        listOf(clients.images, clients.downloads, clients.shared).forEach { get(it, "verified.jpg") }
        get(clients.images, "other.jpg")

        assertEquals(listOf("browser", "browser", "browser", "catalog"), agents.toList())
    }
}
