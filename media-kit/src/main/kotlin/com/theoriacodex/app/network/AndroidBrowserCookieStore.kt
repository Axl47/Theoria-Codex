package com.theoriacodex.app.network

import android.webkit.CookieManager
import com.theoriacodex.sources.http.BrowserCookieStore
import com.theoriacodex.sources.http.HostOutageTracker
import java.net.URI

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
