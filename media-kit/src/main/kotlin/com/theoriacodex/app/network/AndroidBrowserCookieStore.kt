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

    /**
     * Overwrites each cookie with an expired one, in every form it can have been stored in: host-only or on the
     * domain it was created with (a `www.` site's can sit on the parent), and, over HTTPS, `Secure` and partitioned.
     * The WebView keeps a `Secure` cookie from being replaced by one without the flag, and a partitioned one (as
     * Cloudflare's clearance is) is only replaced within its partition.
     */
    override fun expire(url: String, name: String?) {
        val address = runCatching { URI(url) }.getOrNull() ?: return
        val host = address.host ?: return
        val domains = listOf("") + setOf(host, HostOutageTracker.normalizeHost(host)).map { "; Domain=$it" }
        val forms = if (address.scheme == "https") listOf("; Secure", "; Secure; Partitioned") else listOf("")
        val names = name?.let(::listOf) ?: cookieHeader(url)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty()
        names.forEach { expired ->
            forms.forEach { form -> domains.forEach { domain -> setCookie(url, "$expired=; Max-Age=0; Path=/$domain$form") } }
        }
        flush()
    }
}
