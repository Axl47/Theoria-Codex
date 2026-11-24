package com.theoriacodex.sources.http

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

class BrowserAddressDns(private val delegate: Dns = Dns.SYSTEM, private val appliesTo: (host: String) -> Boolean = { true }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!appliesTo(hostname) || addresses.firstOrNull() !is Inet4Address) return addresses
        return addresses.filterIsInstance<Inet4Address>()
    }
}
