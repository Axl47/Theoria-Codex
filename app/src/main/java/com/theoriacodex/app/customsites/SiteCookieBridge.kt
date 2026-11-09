package com.theoriacodex.app.customsites

import android.webkit.CookieManager
import com.theoriacodex.sources.http.HostOutageTracker
import java.net.CookieHandler
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
        val names = cookieHeader(url)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty()
        // A cookie only expires when set with the same Domain attribute it was created with.
        names.forEach { name ->
            setCookie(url, "$name=; Max-Age=0; Path=/")
            setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$host")
        }
        flush()
    }
}

/**
 * Lets the clearance cookies a person earned in the in-app browser apply to the app's own requests, for
 * saved custom sites (and the one site being set up) and their subdomains, such as media CDNs. Every
 * other host gets no cookies, so built-in sources behave exactly as before. The allowed hosts come from
 * [updateSiteHosts] and [setSetupHost]; nothing is stored here. Every store call is guarded: a missing
 * or updating WebView means "no cookies", never an exception on a request thread.
 *
 * [cookieHandler] covers `HttpURLConnection` (custom-site API and offline downloads) and [cookieJar]
 * covers OkHttp (Coil images and Media3 video). Both read and write only through the same gate.
 */
internal class SiteCookieBridge(private val store: BrowserCookieStore) {
    @Volatile
    private var siteHosts: Set<String> = emptySet()

    @Volatile
    private var setupHost: String? = null

    /** The hosts of every saved custom site. */
    fun updateSiteHosts(hosts: Set<String>) {
        siteHosts = hosts.mapTo(HashSet(), HostOutageTracker::normalizeHost)
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
    fun cookiesFor(url: String): String? =
        url.takeIf { isAllowed(runCatching { URI(it).host }.getOrNull()) }?.let { runCatching { store.cookieHeader(it) }.getOrNull() }

    private fun isAllowed(host: String?): Boolean {
        if (host == null) return false
        return (siteHosts + setupHost).any { it != null && HostOutageTracker.hostBelongsToSite(host, it) }
    }

    private fun storeCookie(url: String, setCookie: String) {
        if (isAllowed(runCatching { URI(url).host }.getOrNull())) runCatching { store.setCookie(url, setCookie) }
    }

    val cookieHandler: CookieHandler = object : CookieHandler() {
        override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> =
            cookiesFor(uri.toString())?.let { mapOf("Cookie" to listOf(it)) } ?: emptyMap()

        // Refreshed clearance cookies go back to the browser's store, so both stay in step.
        override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
            responseHeaders.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                .values.flatten().forEach { storeCookie(uri.toString(), it) }
        }
    }

    val cookieJar: CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookiesFor(url.toString())?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { storeCookie(url.toString(), it.toString()) }
        }
    }
}
