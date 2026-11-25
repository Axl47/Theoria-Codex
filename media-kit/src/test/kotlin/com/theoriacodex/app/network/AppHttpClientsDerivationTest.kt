package com.theoriacodex.app.network

import com.theoriacodex.sources.http.BrowserAddressDns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHttpClientsDerivationTest {
    private val lookups = CopyOnWriteArrayList<String>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    private fun v4(host: String) = InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 0, 2, 1))
    private fun v6(host: String) = InetAddress.getByAddress(host, ByteArray(16) { if (it == 15) 1 else 0x20 })

    private fun clients(resolver: Dns) = AppHttpClients(
        jar,
        memory = ConnectionMemory(
            object : RecentHostStore {
                override fun load() = listOf("plain.example", "cleared.example")
                override fun save(hosts: List<String>) = Unit
            },
            activeNetwork = { null },
            resolver = resolver,
        ),
        sharesBrowserIdentity = { it == "cleared.example" },
    )

    @Test
    fun `every client shares the one connection pool, cookie jar and address lookup (ADR 0102)`() {
        val memory = ConnectionMemory(NoRecentHosts, activeNetwork = { null })
        val clients = AppHttpClients(jar, memory = memory)
        val call = clients.shared.newCall(Request.Builder().url("https://example.com/").build())

        assertTrue(clients.shared.dns is BrowserAddressDns)
        listOf(clients.shared, clients.video, clients.sources, clients.images, clients.downloads).forEach { client ->
            assertSame(clients.shared.connectionPool, client.connectionPool)
            assertSame(jar, client.cookieJar)
            assertSame(clients.shared.dns, client.dns)
            assertTrue("IPv6 and IPv4 race on each new connection", client.fastFallback)
            assertSame("Connections teach the memory hosts and IPv6 health", memory, client.eventListenerFactory.create(call))
        }
    }

    @Test
    fun `a host holding a browser identity is looked up as the WebView would (ADR 0102)`() {
        val clients = clients(Dns { host -> listOf(v4(host), v6(host)) })

        assertEquals(listOf(4), clients.shared.dns.lookup("cleared.example").map { it.address.size })
        assertEquals(listOf(4, 16), clients.shared.dns.lookup("plain.example").map { it.address.size })
    }

    @Test
    fun `each client keeps its own queueing, timeouts and redirect policy (ADR 0102)`() {
        val clients = clients(Dns.SYSTEM)

        val defaults = Dispatcher()
        assertTrue("Images get more than the default per-host limit", clients.images.dispatcher.maxRequestsPerHost > defaults.maxRequestsPerHost)
        assertEquals("Sources never queue behind a host", clients.sources.dispatcher.maxRequests, clients.sources.dispatcher.maxRequestsPerHost)
        assertTrue("Sources get more than the default per-host limit", clients.sources.dispatcher.maxRequestsPerHost > defaults.maxRequestsPerHost)
        assertNotEquals(clients.shared.connectTimeoutMillis, clients.downloads.connectTimeoutMillis)
        assertNotEquals(clients.shared.readTimeoutMillis, clients.downloads.readTimeoutMillis)
        assertFalse(clients.downloads.followSslRedirects)
        assertTrue(clients.shared.followSslRedirects)
    }

    @Test
    fun `warm-up reaches each recent host once per launch and skips hosts holding a browser identity (ADR 0102)`() {
        val clients = clients(Dns { host -> lookups += host; throw UnknownHostException(host) })

        runBlocking {
            clients.warmRecentHosts()
            clients.warmRecentHosts()
        }

        assertEquals(listOf("plain.example"), lookups.toList())
    }
}
