package com.theoriacodex.app.network

import android.content.Context
import androidx.core.content.edit
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Protocol

/** Where the hosts the app last connected to are kept between launches, newest first. */
internal interface RecentHostStore {
    fun load(): List<String>
    fun save(hosts: List<String>)
}

/** For code that never launches again, such as tests. */
internal object NoRecentHosts : RecentHostStore {
    override fun load(): List<String> = emptyList()
    override fun save(hosts: List<String>) = Unit
}

internal class SharedPreferencesRecentHostStore(context: Context) : RecentHostStore {
    // Built with the Application, before its context can open preferences.
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    override fun load(): List<String> = preferences.getString(KEY_HOSTS, null)?.split('\n').orEmpty().filter(String::isNotBlank)

    override fun save(hosts: List<String>) = preferences.edit { putString(KEY_HOSTS, hosts.joinToString("\n")) }

    private companion object {
        const val PREFERENCES_NAME = "connection_memory"
        const val KEY_HOSTS = "recent_hosts"
    }
}

/**
 * What the app's own connections teach it, for the first requests after a launch that would otherwise wait on each
 * one: which hosts it uses, so the next launch can connect ahead of need, and whether IPv6 gets through here.
 *
 * A network can offer IPv6 and then drop it; a half-configured router is enough. OkHttp's race turns that into a
 * quarter-second wait per new connection. Once IPv4 has beaten IPv6 on [IPV6_LOSSES_TO_AVOID] sites that offer both,
 * IPv6 stops being offered for [IPV6_AVOID_MS], then gets another chance. Another network starts with a clean slate.
 */
internal class ConnectionMemory(
    private val store: RecentHostStore,
    private val activeNetwork: () -> Any?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resolver: Dns = Dns.SYSTEM,
) : EventListener(), Dns {
    private val dualStackHosts = ConcurrentHashMap.newKeySet<String>()
    private val recent by lazy { store.load().toMutableList() }
    private var network: Any? = null
    private var ipv6Losses = 0
    private var avoidIpv6Until = 0L

    fun recentHosts(): List<String> = synchronized(this) { recent.toList() }

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver.lookup(hostname)
        return if (avoidingIpv6()) addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses } else addresses
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        if (inetAddressList.any { it is Inet6Address } && inetAddressList.any { it is Inet4Address }) {
            dualStackHosts += domainName
        }
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        val host = inetSocketAddress.hostString
        remember(host)
        when {
            inetSocketAddress.address is Inet6Address -> ipv6Answered()
            host in dualStackHosts -> ipv4Won()
        }
    }

    /** With IPv6 ruled out, IPv4 is the only way left, so a failure there means this judgement can't be trusted. */
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: java.io.IOException,
    ) {
        if (inetSocketAddress.address is Inet4Address) ipv6Answered()
    }

    private fun remember(host: String) = synchronized(this) {
        if (recent.firstOrNull() == host) return
        recent.remove(host)
        recent.add(0, host)
        while (recent.size > MAX_RECENT_HOSTS) recent.removeAt(recent.lastIndex)
        store.save(recent.toList())
    }

    private fun avoidingIpv6(): Boolean = synchronized(this) {
        forgetOtherNetworks()
        clock() < avoidIpv6Until
    }

    private fun ipv6Answered() = synchronized(this) {
        forgetOtherNetworks()
        ipv6Losses = 0
        avoidIpv6Until = 0L
    }

    private fun ipv4Won() = synchronized(this) {
        forgetOtherNetworks()
        if (clock() < avoidIpv6Until) return
        ipv6Losses++
        if (ipv6Losses >= IPV6_LOSSES_TO_AVOID) {
            ipv6Losses = 0
            avoidIpv6Until = clock() + IPV6_AVOID_MS
        }
    }

    private fun forgetOtherNetworks() {
        val current = activeNetwork()
        if (current == network) return
        network = current
        ipv6Losses = 0
        avoidIpv6Until = 0L
    }

    private companion object {
        const val MAX_RECENT_HOSTS = 12
        const val IPV6_LOSSES_TO_AVOID = 2
        const val IPV6_AVOID_MS = 10 * 60 * 1000L
    }
}
