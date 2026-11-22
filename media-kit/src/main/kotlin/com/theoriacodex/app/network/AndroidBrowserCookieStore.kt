package com.theoriacodex.app.network

import android.webkit.CookieManager
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.HostOutageTracker
import java.net.URI

/**
 * The system WebView's [CookieManager], which the in-app browser writes to and the app's HTTP clients read through
 * their cookie jars. It can throw when the WebView is missing or updating; callers guard every call.
 */
object AndroidBrowserCookieStore : BrowserCookieStore {
    override fun cookieHeader(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)

    override fun setCookie(url: String, setCookie: String) {
        CookieManager.getInstance().setCookie(url, setCookie)
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }

    override fun expire(url: String, name: String?) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        // A cookie set on the parent domain (a `www.` site's) only expires with the Domain it was created with.
        val domains = setOf(host, HostOutageTracker.normalizeHost(host))
        val names = name?.let(::listOf) ?: cookieHeader(url)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty()
        names.forEach { expired ->
            setCookie(url, "$expired=; Max-Age=0; Path=/")
            domains.forEach { setCookie(url, "$expired=; Max-Age=0; Path=/; Domain=$it") }
        }
        flush()
    }
}
