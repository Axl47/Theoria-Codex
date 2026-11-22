package com.theoriacodex.app.series

import com.theoriacodex.domain.adapter.BrowserCheck
import com.theoriacodex.sources.http.BrowserCookieStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserChecksTest {
    private var clock = 0L
    private var flushes = 0
    private val cookies = OneSiteCookies()
    private val checks = BrowserChecks(cookies, flushCookies = { flushes++ }) { clock }
    private val check = BrowserCheck("https://www.site.test/list", "agent/1")

    @Test
    fun `a page the site lets through closes the check, keeps the cookies and retries the load once`() {
        var retries = 0
        checks.open("Site", check) { retries++ }
        val opened = checks.opened!!

        checks.pageLoaded(opened, letThrough = false)
        assertNotNull(checks.opened)
        checks.pageLoaded(opened, letThrough = true)
        checks.pageLoaded(opened, letThrough = true)

        assertNull(checks.opened)
        assertEquals(1, retries)
        assertEquals(1, flushes)
    }

    @Test
    fun `a new clearance closes the check even on a page the site answers with an error`() {
        var retries = 0
        cookies.header = "cf_clearance=refused"
        checks.open("Site", check) { retries++ }
        val opened = checks.opened!!

        checks.pageLoaded(opened, letThrough = false)
        assertNotNull(checks.opened)
        cookies.header = "cf_clearance=earned"
        checks.pageLoaded(opened, letThrough = false)

        assertNull(checks.opened)
        assertEquals(1, retries)
    }

    @Test
    fun `a site the browser got through to is past a browser's help for a few minutes`() {
        checks.open("Site", check) {}
        checks.pageLoaded(checks.opened!!, letThrough = true)

        assertTrue(checks.cannotHelp(BrowserCheck("https://site.test/other", "agent/1")))
        assertFalse(checks.cannotHelp(BrowserCheck("https://other.test/", "agent/1")))
        clock += 5 * 60 * 1000L
        assertFalse(checks.cannotHelp(check))
    }

    @Test
    fun `closing by hand retries nothing and changes nothing`() {
        var retries = 0
        checks.open("Site", check) { retries++ }
        checks.close()

        assertNull(checks.opened)
        assertEquals(0, retries)
        assertFalse(checks.cannotHelp(check))
    }
}

/** One `Cookie` header for every address. */
private class OneSiteCookies(var header: String? = null) : BrowserCookieStore {
    override fun cookieHeader(url: String) = header
    override fun setCookie(url: String, setCookie: String) = Unit
    override fun flush() = Unit
    override fun expire(url: String, name: String?) = Unit
}
