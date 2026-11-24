package com.theoriacodex.app.customsites

import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.sources.http.BrowserCookieJar
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.HostOutageTracker
import java.net.URI
import okhttp3.CookieJar

class SiteMediaKey(val address: SiteAddress, val authorization: String)

class SiteCookieBridge(private val store: BrowserCookieStore) {
    @Volatile
    private var siteHosts: Set<String> = emptySet()

    @Volatile
    private var builtInAgents: Map<String, String> = emptyMap()

    @Volatile
    private var mediaKeys: List<SiteMediaKey> = emptyList()

    @Volatile
    private var setupHost: String? = null

    @Volatile
    private var checkHost: String? = null

    fun updateSiteHosts(hosts: Set<String>) {
        siteHosts = hosts.mapTo(HashSet(), HostOutageTracker::normalizeHost)
    }

    fun updateBuiltInAgents(agents: Map<String, String>) {
        builtInAgents = agents.mapKeys { (host, _) -> HostOutageTracker.normalizeHost(host) }
    }

    fun userAgentFor(url: String): String? {
        val agents = builtInAgents
        if (agents.isEmpty()) return null
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        return agents.entries.firstOrNull { (site, _) -> HostOutageTracker.hostBelongsToSite(host, site) }?.value
    }

    fun updateMediaKeys(keys: List<SiteMediaKey>) {
        mediaKeys = keys
    }

    fun mediaAuthorizationFor(url: String): String? {
        val keys = mediaKeys
        if (keys.isEmpty()) return null
        val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme.equals("https", ignoreCase = true) } ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeUnless { it == -1 || it == HTTPS_PORT }
        return keys.singleOrNull { it.address.port == port && it.covers(host, uri.rawPath.orEmpty()) }?.authorization
    }

    private fun SiteMediaKey.covers(host: String, path: String): Boolean {
        val siteHost = HostOutageTracker.normalizeHost(address.host)
        if (HostOutageTracker.normalizeHost(host) != siteHost) return HostOutageTracker.hostBelongsToSite(host, siteHost)
        return path == address.path || path.startsWith("${address.path}/")
    }

    fun setSetupHost(host: String?) {
        setupHost = host?.let(HostOutageTracker::normalizeHost)
    }

    fun setCheckHost(host: String?) {
        checkHost = host?.let(HostOutageTracker::normalizeHost)
    }

    fun flush() {
        runCatching { store.flush() }
    }

    fun forget(host: String) {
        runCatching { store.expire("https://$host/") }
    }

    fun cookiesFor(url: String): String? =
        if (isAllowed(runCatching { URI(url).host }.getOrNull())) runCatching { store.cookieHeader(url) }.getOrNull() else null

    fun isAllowed(host: String?): Boolean {
        if (host == null) return false
        return keepsCookies(host) || setupHost.covers(host) || checkHost.covers(host)
    }

    private fun String?.covers(host: String): Boolean = this != null && HostOutageTracker.hostBelongsToSite(host, this)

    fun keepsCookies(host: String): Boolean =
        siteHosts.any { HostOutageTracker.hostBelongsToSite(host, it) } ||
            builtInAgents.keys.any { HostOutageTracker.hostBelongsToSite(host, it) }

    val cookieJar: CookieJar = BrowserCookieJar(store, ::isAllowed)
}

interface SiteCookieBridgeOwner {
    val siteCookieBridge: SiteCookieBridge
}

private const val HTTPS_PORT = 443
