package com.theoriacodex.app.customsites

import android.webkit.CookieManager
import com.theoriacodex.domain.source.SiteAddress
import com.theoriacodex.sources.http.HostOutageTracker
import java.net.URI
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The cookie store the in-app browser writes to, as the bridge needs it. A port so the bridge's rules
 * are tested without `android.webkit`; [AndroidBrowserCookieStore] is the real one.
 */
internal interface BrowserCookieStore {
    /** The `Cookie` header value for [url], or null when there are none. */
    fun cookieHeader(url: String): String?

    /** Stores one `Set-Cookie` value for [url]. */
    fun setCookie(url: String, setCookie: String)

    fun flush()

    /** Expires every cookie [url] would send. */
    fun expireAll(url: String)
}

/** The system WebView's [CookieManager]. It can throw when the WebView is missing or updating; [SiteCookieBridge] guards for that. */
internal object AndroidBrowserCookieStore : BrowserCookieStore {
    override fun cookieHeader(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)

    override fun setCookie(url: String, setCookie: String) {
        CookieManager.getInstance().setCookie(url, setCookie)
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }

    override fun expireAll(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        // A cookie set on the parent domain (a `www.` site's) only expires with the Domain it was created with.
        val domains = setOf(host, HostOutageTracker.normalizeHost(host))
        val names = cookieHeader(url)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty()
        names.forEach { name ->
            setCookie(url, "$name=; Max-Age=0; Path=/")
            domains.forEach { setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$it") }
        }
        flush()
    }
}

/** One saved site's `Authorization` value. A plain class, so the value can't reach a log through `toString`. */
internal class SiteMediaKey(val address: SiteAddress, val authorization: String)

/**
 * Lets the clearance cookies a person earned in the in-app browser apply to the app's own requests, for
 * saved custom sites, built-in sites verified in the browser and the one site being set up, plus their
 * subdomains, such as media CDNs. Every other host gets no cookies, so an unverified built-in source
 * behaves exactly as before. The allowed hosts come from [updateSiteHosts], [updateBuiltInAgents] and
 * [setSetupHost]; nothing is stored here. Every store call is guarded: a missing or updating WebView
 * means "no cookies", never an exception on a request thread.
 *
 * Every OkHttp client in the app carries [cookieJar], which reads and writes only through that gate.
 */
internal class SiteCookieBridge(private val store: BrowserCookieStore) {
    @Volatile
    private var siteHosts: Set<String> = emptySet()

    /** Verified built-in sites' hosts and the browser agent each clearance is bound to. */
    @Volatile
    private var builtInAgents: Map<String, String> = emptyMap()

    @Volatile
    private var mediaKeys: List<SiteMediaKey> = emptyList()

    @Volatile
    private var setupHost: String? = null

    /** The hosts of every saved custom site. */
    fun updateSiteHosts(hosts: Set<String>) {
        siteHosts = hosts.mapTo(HashSet(), HostOutageTracker::normalizeHost)
    }

    /** The built-in sites verified in the browser, by host, with the agent that earned each clearance. */
    fun updateBuiltInAgents(agents: Map<String, String>) {
        builtInAgents = agents.mapKeys { (host, _) -> HostOutageTracker.normalizeHost(host) }
    }

    /** The agent a verified built-in site's clearance needs on [url], or null for any other site. */
    fun userAgentFor(url: String): String? {
        val agents = builtInAgents
        if (agents.isEmpty()) return null
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        return agents.entries.firstOrNull { (site, _) -> HostOutageTracker.hostBelongsToSite(host, site) }?.value
    }

    /** The saved sites whose API accepts their key in an `Authorization` header. */
    fun updateMediaKeys(keys: List<SiteMediaKey>) {
        mediaKeys = keys
    }

    /**
     * The value a media request to [url] may offer: HTTPS only, on one saved site's own port and hosts, and under
     * its install path on its main host. Installs that can't be told apart get no key, so none reaches another.
     */
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

    /** The site being verified before it is saved, or null once that ends. */
    fun setSetupHost(host: String?) {
        setupHost = host?.let(HostOutageTracker::normalizeHost)
    }

    fun flush() {
        runCatching { store.flush() }
    }

    /** Drops [host]'s cookies from the shared store, for a site that was removed. */
    fun forget(host: String) {
        runCatching { store.expireAll("https://$host/") }
    }

    /** The cookies the in-app browser holds for [url], when its host is allowed. */
    fun cookiesFor(url: String): String? = cookiesFor(url, runCatching { URI(url).host }.getOrNull())

    /** [host] is the already-parsed host of [url]; callers on the request path never re-parse it. */
    private fun cookiesFor(url: String, host: String?): String? =
        if (isAllowed(host)) runCatching { store.cookieHeader(url) }.getOrNull() else null

    /** Whether [host] shares the browser's identity. On every image and API request: no allocation, and no parsing beyond the host's normalization. */
    fun isAllowed(host: String?): Boolean {
        if (host == null) return false
        return siteHosts.any { HostOutageTracker.hostBelongsToSite(host, it) } ||
            builtInAgents.keys.any { HostOutageTracker.hostBelongsToSite(host, it) } ||
            setupHost?.let { HostOutageTracker.hostBelongsToSite(host, it) } == true
    }

    private fun storeCookie(url: String, host: String?, setCookie: String) {
        if (isAllowed(host)) runCatching { store.setCookie(url, setCookie) }
    }

    val cookieJar: CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookiesFor(url.toString(), url.host)?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()

        // Refreshed clearance cookies go back to the browser's store, so both stay in step.
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { storeCookie(url.toString(), url.host, it.toString()) }
        }
    }
}

private const val HTTPS_PORT = 443
