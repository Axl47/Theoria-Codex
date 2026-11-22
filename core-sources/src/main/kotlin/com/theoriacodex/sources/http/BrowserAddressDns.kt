package com.theoriacodex.sources.http

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

/**
 * Resolves the hosts [appliesTo] accepts to the addresses the system WebView connects to, so a clearance cookie a
 * browser check earned is sent from the network address it was earned from. Cloudflare binds that cookie to the
 * address, and on a network where IPv6 and IPv4 reach the internet from different addresses, a request over the
 * other family is refused with the very same cookie.
 *
 * The WebView connects in the order [delegate] (the system resolver) lists the addresses. OkHttp's fast fallback
 * instead tries IPv6 first whatever that order is. So when the system lists IPv4 first, as it does where it ranks the
 * device's IPv6 below its IPv4, IPv6 is left out; when it lists IPv6 first, both try IPv6 first and fall back to IPv4
 * alike, and every address is kept.
 */
class BrowserAddressDns(private val delegate: Dns = Dns.SYSTEM, private val appliesTo: (host: String) -> Boolean = { true }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!appliesTo(hostname) || addresses.firstOrNull() !is Inet4Address) return addresses
        return addresses.filterIsInstance<Inet4Address>()
    }
}
