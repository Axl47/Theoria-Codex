package com.theoriacodex.app.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionMemoryTest {
    private val call = OkHttpClient().newCall(Request.Builder().url("https://example.com/").build())
    private var now = 0L
    private var network = "wifi"
    private val saved = mutableListOf<List<String>>()

    private val store = object : RecentHostStore {
        override fun load() = listOf("earlier.example")
        override fun save(hosts: List<String>) { saved += hosts }
    }

    private fun v4(host: String) = InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 0, 2, 1))
    private fun v6(host: String) = InetAddress.getByAddress(host, ByteArray(16) { if (it == 15) 1 else 0x20 })

    private val memory = ConnectionMemory(
        store,
        activeNetwork = { network },
        clock = { now },
        resolver = Dns { host -> if (host == "ipv6only.example") listOf(v6(host)) else listOf(v6(host), v4(host)) },
    )

    private fun ipv4Beats(host: String) {
        memory.dnsEnd(call, host, listOf(v6(host), v4(host)))
        memory.connectEnd(call, InetSocketAddress(v4(host), 443), Proxy.NO_PROXY, Protocol.HTTP_2)
    }

    private fun families(host: String) = memory.lookup(host).map { it.address.size }

    @Test
    fun `IPv6 is set aside once IPv4 keeps winning, then gets another chance`() {
        ipv4Beats("a.example")
        assertEquals("One lost race proves nothing", listOf(16, 4), families("c.example"))

        ipv4Beats("b.example")
        assertEquals(listOf(4), families("c.example"))

        now += 10 * 60 * 1000L
        assertEquals(listOf(16, 4), families("c.example"))
    }

    @Test
    fun `a site reached over IPv6 proves it works`() {
        ipv4Beats("a.example")
        memory.connectEnd(call, InetSocketAddress(v6("a.example"), 443), Proxy.NO_PROXY, Protocol.HTTP_2)
        ipv4Beats("b.example")

        assertEquals(listOf(16, 4), families("c.example"))
    }

    @Test
    fun `sites with nothing but IPv6 stay reachable`() {
        ipv4Beats("a.example")
        ipv4Beats("b.example")

        assertEquals(listOf(16), families("ipv6only.example"))
    }

    @Test
    fun `another network is judged on its own`() {
        ipv4Beats("a.example")
        ipv4Beats("b.example")

        network = "cellular"

        assertEquals(listOf(16, 4), families("c.example"))
    }

    @Test
    fun `IPv4 failing while IPv6 is set aside brings IPv6 back`() {
        ipv4Beats("a.example")
        ipv4Beats("b.example")

        memory.connectFailed(call, InetSocketAddress(v4("a.example"), 443), Proxy.NO_PROXY, null, java.io.IOException("refused"))

        assertEquals(listOf(16, 4), families("c.example"))
    }

    @Test
    fun `recent hosts stay newest first and bounded`() {
        repeat(14) { memory.connectEnd(call, InetSocketAddress(v4("host$it.example"), 443), Proxy.NO_PROXY, Protocol.HTTP_2) }
        memory.connectEnd(call, InetSocketAddress(v4("host5.example"), 443), Proxy.NO_PROXY, Protocol.HTTP_2)

        val hosts = memory.recentHosts()
        assertEquals("host5.example", hosts.first())
        assertEquals(12, hosts.size)
        assertEquals(hosts, saved.last())
    }
}
