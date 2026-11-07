package com.theoriacodex.app.customsites

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import java.net.CookieHandler
import java.net.URI
import java.util.Locale
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * SPIKE: sites whose browser challenge the user passed in an in-app WebView. Requests to these
 * hosts (and their subdomains, for media CDNs) carry the WebView's cookies, so the clearance the
 * person earned applies to API, image and video requests. Every other host gets no cookies, so
 * built-in sources behave exactly as before.
 *
 * Covers HttpURLConnection (custom-site API and Media3 video) through [CookieHandler.setDefault],
 * and Coil's OkHttp through [cookieJar].
 */
object BrowserVerifiedSites {
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var hosts: Set<String> = emptySet()

    fun install(context: Context) {
        prefs = context.getSharedPreferences("browser_verified_sites", Context.MODE_PRIVATE)
        hosts = prefs.getStringSet(KEY_HOSTS, emptySet()).orEmpty().toSet()
        CookieHandler.setDefault(Handler)
    }

    /** Called after the user finishes the WebView check for [host]. */
    fun markVerified(host: String) {
        hosts = hosts + siteHost(host)
        prefs.edit().putStringSet(KEY_HOSTS, hosts).apply()
        CookieManager.getInstance().flush()
    }

    private fun isVerified(host: String?): Boolean {
        val candidate = host?.let(::siteHost) ?: return false
        return hosts.any { candidate == it || candidate.endsWith(".$it") }
    }

    private fun cookiesFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf(String::isNotBlank)

    private object Handler : CookieHandler() {
        override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> {
            if (!isVerified(uri.host)) return emptyMap()
            return cookiesFor(uri.toString())?.let { mapOf("Cookie" to listOf(it)) } ?: emptyMap()
        }

        // Refreshed clearance cookies go back to the WebView store, so both stay in step.
        override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
            if (!isVerified(uri.host)) return
            val manager = CookieManager.getInstance()
            responseHeaders.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                .values.flatten().forEach { manager.setCookie(uri.toString(), it) }
        }
    }

    val cookieJar: CookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!isVerified(url.host)) return emptyList()
            val header = cookiesFor(url.toString()) ?: return emptyList()
            return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!isVerified(url.host)) return
            val manager = CookieManager.getInstance()
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        }
    }

    private fun siteHost(host: String): String = host.lowercase(Locale.ROOT).removePrefix("www.")

    private const val KEY_HOSTS = "hosts"
}
