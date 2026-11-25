package com.theoriacodex.sources.http

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserCookieJarTest {
    private val url = "https://site.test/list".toHttpUrl()
    private val store = object : BrowserCookieStore {
        var header: String? = null
        var throwing = false
        val written = mutableListOf<String>()

        override fun cookieHeader(url: String): String? = header.also { check(!throwing) { "WebView updating" } }

        override fun setCookie(url: String, setCookie: String) {
            check(!throwing) { "WebView updating" }
            written += setCookie
        }

        override fun flush() = Unit

        override fun expire(url: String, name: String?) = Unit
    }

    @Test
    fun `the browser's cookie header becomes the request's cookies, and refreshed ones go back`() {
        store.header = "session=abc; cf_clearance=xyz"
        val jar = BrowserCookieJar(store) { true }

        assertEquals(listOf("session" to "abc", "cf_clearance" to "xyz"), jar.loadForRequest(url).map { it.name to it.value })
        jar.saveFromResponse(url, listOfNotNull(Cookie.parse(url, "cf_clearance=new; Path=/")))
        assertEquals(listOf("cf_clearance=new; path=/"), store.written)
    }

    @Test
    fun `a host the jar does not allow neither sends nor keeps cookies (ADR 0050)`() {
        store.header = "session=abc"
        val jar = BrowserCookieJar(store) { it != "site.test" }

        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
        jar.saveFromResponse(url, listOfNotNull(Cookie.parse(url, "session=new")))
        assertEquals(emptyList<String>(), store.written)
    }

    @Test
    fun `a store that cannot answer means no cookies, never an exception (ADR 0050)`() {
        store.throwing = true
        val jar = BrowserCookieJar(store) { true }

        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
        jar.saveFromResponse(url, listOfNotNull(Cookie.parse(url, "session=new")))
        assertNull(store.cookie(url.toString(), CLEARANCE_COOKIE))
    }

    @Test
    fun `one cookie is read by its exact name`() {
        store.header = "xcf_clearance=no; cf_clearance=a=b"

        assertEquals("a=b", store.cookie(url.toString(), CLEARANCE_COOKIE))
        assertNull(store.cookie(url.toString(), "missing"))
    }
}
