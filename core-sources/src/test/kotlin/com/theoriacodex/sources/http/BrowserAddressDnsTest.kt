package com.theoriacodex.sources.http

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressDnsTest {
    private val v4 = InetAddress.getByName("192.0.2.1")
    private val v6 = InetAddress.getByName("2001:db8::1")

    @Test
    fun `where the system lists IPv4 first, IPv6 is left out as the browser leaves it (ADR 0050)`() {
        assertEquals(listOf(v4), BrowserAddressDns(answering(v4, v6)).lookup("site.test"))
    }

    @Test
    fun `where the system lists IPv6 first, every address is kept (ADR 0050)`() {
        assertEquals(listOf(v6, v4), BrowserAddressDns(answering(v6, v4)).lookup("site.test"))
    }

    @Test
    fun `hosts it does not apply to keep every address`() {
        assertEquals(listOf(v4, v6), BrowserAddressDns(answering(v4, v6)) { false }.lookup("site.test"))
    }

    private fun answering(vararg addresses: InetAddress) = Dns { addresses.toList() }
}
