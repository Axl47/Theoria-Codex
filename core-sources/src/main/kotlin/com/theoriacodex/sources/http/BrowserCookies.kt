package com.theoriacodex.sources.http

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The cookie store the in-app browser writes to: the system WebView's, on a device. A port so the rules built on it
 * are tested without `android.webkit`; the app's `AndroidBrowserCookieStore` is the real one. Every call may throw
 * while the WebView is missing or updating, so callers guard each one.
 */
interface BrowserCookieStore {
    /** The `Cookie` header value for [url], or null when there are none. */
    fun cookieHeader(url: String): String?

    /** Stores one `Set-Cookie` value for [url]. */
    fun setCookie(url: String, setCookie: String)

    fun flush()

    /** Expires the cookie called [name] that [url] would send, or every one of them when [name] is null. */
    fun expire(url: String, name: String? = null)
}

/** The cookie a Cloudflare browser check leaves once it is passed. */
const val CLEARANCE_COOKIE = "cf_clearance"

/** The value of the cookie called [name] that [url] would send; null when there is none or the store cannot answer. */
fun BrowserCookieStore.cookie(url: String, name: String): String? =
    runCatching { cookieHeader(url) }.getOrNull()
        ?.split(';')
        ?.firstNotNullOfOrNull { pair -> pair.trim().takeIf { it.substringBefore('=') == name }?.substringAfter('=') }

/**
 * Whether [url] now carries a clearance cookie other than [old], the sign that its browser check was passed. Only a
 * new value counts, since the old one may be the very cookie the site refused.
 */
fun BrowserCookieStore.hasNewClearance(url: String, old: String?): Boolean =
    cookie(url, CLEARANCE_COOKIE).let { it != null && it != old }

/**
 * An OkHttp cookie jar over [store] for the hosts [allows] accepts; every other host gets no cookies and keeps none.
 * Refreshed cookies go back to the store, so the browser and the client stay in step. A store that throws means "no
 * cookies", never an exception on a request thread.
 */
class BrowserCookieJar(private val store: BrowserCookieStore, private val allows: (host: String) -> Boolean) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!allows(url.host)) return emptyList()
        val header = runCatching { store.cookieHeader(url.toString()) }.getOrNull() ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!allows(url.host)) return
        cookies.forEach { cookie -> runCatching { store.setCookie(url.toString(), cookie.toString()) } }
    }
}
