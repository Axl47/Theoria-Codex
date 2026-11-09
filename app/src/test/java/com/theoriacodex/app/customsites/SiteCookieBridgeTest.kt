package com.theoriacodex.app.customsites

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A [BrowserCookieStore] over a map keyed by host; records writes, flushes and expiries. */
internal class FakeBrowserCookieStore : BrowserCookieStore {
    val cookies = mutableMapOf<String, String>()
    val written = mutableListOf<Pair<String, String>>()
    val expired = mutableListOf<String>()
    var flushes = 0
    var throwing = false

    override fun cookieHeader(url: String): String? {
        check(!throwing) { "WebView unavailable" }
        return cookies[url.toHttpUrl().host]
    }

    override fun setCookie(url: String, setCookie: String) {
        check(!throwing) { "WebView unavailable" }
        written += url to setCookie
    }

    override fun flush() {
        check(!throwing) { "WebView unavailable" }
        flushes++
    }

    override fun expireAll(url: String) {
        check(!throwing) { "WebView unavailable" }
        expired += url
    }
}

class SiteCookieBridgeTest {
    private val store = FakeBrowserCookieStore().apply {
        cookies["safebooru.org"] = "cf=1"
        cookies["www.safebooru.org"] = "cf=2"
        cookies["cdn.safebooru.org"] = "cf=3"
        cookies["notsafebooru.org"] = "cf=4"
        cookies["other.example"] = "cf=5"
    }
    private val bridge = SiteCookieBridge(store)

    private fun sent(url: String): Map<String, List<String>> =
        bridge.cookiesFor(url)?.let { mapOf("Cookie" to listOf(it)) } ?: emptyMap()

    private fun receive(url: String, setCookie: String) {
        val httpUrl = url.toHttpUrl()
        bridge.cookieJar.saveFromResponse(httpUrl, listOf(requireNotNull(Cookie.parse(httpUrl, setCookie))))
    }

    @Test
    fun `only saved sites and their subdomains read and write cookies`() {
        bridge.updateSiteHosts(setOf("www.Safebooru.org"))

        assertEquals(mapOf("Cookie" to listOf("cf=1")), sent("https://safebooru.org/"))
        assertEquals(mapOf("Cookie" to listOf("cf=2")), sent("https://www.safebooru.org/"))
        assertEquals(mapOf("Cookie" to listOf("cf=3")), sent("https://cdn.safebooru.org/a.jpg"))
        assertEquals(emptyMap<String, List<String>>(), sent("https://notsafebooru.org/"))
        assertEquals(emptyMap<String, List<String>>(), sent("https://other.example/"))
        assertTrue(bridge.cookieJar.loadForRequest("https://other.example/".toHttpUrl()).isEmpty())
        assertEquals("cf=3", bridge.cookieJar.loadForRequest("https://cdn.safebooru.org/".toHttpUrl()).single().toString().substringBefore(";"))

        receive("https://other.example/", "cf=x")
        assertTrue(store.written.isEmpty())
        receive("https://cdn.safebooru.org/", "cf=y")
        assertEquals(listOf("https://cdn.safebooru.org/" to "cf=y; path=/"), store.written)
    }

    @Test
    fun `a URL with characters java net URI rejects still gets its cookies through the jar`() {
        bridge.updateSiteHosts(setOf("safebooru.org"))
        val url = "https://safebooru.org/index.php?tags=a|b".toHttpUrl()

        assertEquals(1, bridge.cookieJar.loadForRequest(url).size)
    }

    @Test
    fun `the setup host is allowed until it is revoked`() {
        assertEquals(emptyMap<String, List<String>>(), sent("https://other.example/"))

        bridge.setSetupHost("other.example")
        assertEquals(mapOf("Cookie" to listOf("cf=5")), sent("https://other.example/"))
        assertEquals("cf=5", bridge.cookiesFor("https://other.example/"))

        bridge.setSetupHost(null)
        assertEquals(emptyMap<String, List<String>>(), sent("https://other.example/"))
        assertEquals(null, bridge.cookiesFor("https://other.example/"))
    }

    @Test
    fun `a store that throws yields no cookies and no exception`() {
        bridge.updateSiteHosts(setOf("safebooru.org"))
        store.throwing = true

        assertEquals(emptyMap<String, List<String>>(), sent("https://safebooru.org/"))
        assertTrue(bridge.cookieJar.loadForRequest("https://safebooru.org/".toHttpUrl()).isEmpty())
        receive("https://safebooru.org/", "cf=x")
        bridge.flush()
        bridge.forget("safebooru.org")
    }
}
