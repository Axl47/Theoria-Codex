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
    private val authorizations = CopyOnWriteArrayList<String?>()
    @Volatile private var refuseWithoutKey = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent")
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            authorizations += authorization
            exchange.sendResponseHeaders(if (refuseWithoutKey && authorization == null) 403 else 200, -1)
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
    fun `media sends a verified site's browser agent while provider requests keep their own`() {
        val clients = AppHttpClients(CookieJar.NO_COOKIES, verifiedUserAgent = { url -> "browser".takeIf { "verified" in url } })

        listOf(clients.images, clients.downloads, clients.video).forEach { get(it, "verified.jpg") }
        get(clients.images, "other.jpg")
        get(clients.sources, "verified.json")

        assertEquals(listOf("browser", "browser", "browser", "catalog", "catalog"), agents.toList())
    }

    @Test
    fun `a refused media request retries once with the key, and the host then gets it straight away`() {
        refuseWithoutKey = true
        val clients = AppHttpClients(CookieJar.NO_COOKIES, siteAuthorization = { "Basic key" })

        assertEquals(200, status(clients.images, "first.jpg"))
        assertEquals(200, status(clients.images, "second.jpg"))

        assertEquals(listOf(null, "Basic key", "Basic key"), authorizations.toList())
    }

    @Test
    fun `media a site serves normally never carries the key, and other clients never offer it`() {
        val clients = AppHttpClients(CookieJar.NO_COOKIES, siteAuthorization = { "Basic key" })

        assertEquals(200, status(clients.images, "open.jpg"))
        refuseWithoutKey = true
        assertEquals(403, status(clients.sources, "api.json"))

        assertEquals(listOf<String?>(null, null), authorizations.toList())
    }

    @Test
    fun `a url with no key allowed is left refused`() {
        refuseWithoutKey = true
        val clients = AppHttpClients(CookieJar.NO_COOKIES, siteAuthorization = { null })

        assertEquals(403, status(clients.images, "other.jpg"))

        assertEquals(listOf<String?>(null), authorizations.toList())
    }

    private fun status(client: OkHttpClient, path: String): Int {
        val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/$path").build()
        return client.newCall(request).execute().use { it.code }
    }
}
