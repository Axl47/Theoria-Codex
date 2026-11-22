package com.theoriacodex.app.series

import com.theoriacodex.domain.adapter.BrowserCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserChecksTest {
    private var clock = 0L
    private var flushes = 0
    private val checks = BrowserChecks(flushCookies = { flushes++ }) { clock }
    private val check = BrowserCheck("https://www.site.test/list", "agent/1")

    @Test
    fun `getting through closes the page, keeps the cookies and retries the load once`() {
        var retries = 0
        checks.open("Site", check) { retries++ }
        val opened = checks.opened!!

        checks.through(opened)
        checks.through(opened)

        assertNull(checks.opened)
        assertEquals(1, retries)
        assertEquals(1, flushes)
    }

    @Test
    fun `a site the browser got through to is past a browser's help for a few minutes`() {
        checks.open("Site", check) {}
        checks.through(checks.opened!!)

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
