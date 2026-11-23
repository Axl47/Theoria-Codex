package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.BrowserCookieStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteChecksTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var cookieHeader: String? = null
    private var gatesOpen = false
    private val tested = mutableListOf<String>()
    private val completed = mutableListOf<String>()
    private val cookieHosts = mutableListOf<String?>()
    private val cookies = object : BrowserCookieStore {
        override fun cookieHeader(url: String) = cookieHeader

        override fun setCookie(url: String, setCookie: String) = Unit

        override fun flush() = Unit

        override fun expire(url: String, name: String?) = Unit
    }
    private val target = SiteCheckTarget(
        title = "Site",
        url = "https://site.test/",
        cookieHost = "site.test",
        passes = { agent -> tested.add(agent).let { gatesOpen } },
        complete = { agent -> completed += agent },
    )
    private val checks = SiteChecks(cookies, cookieHosts::add, targetFor = { target }, coroutineScope = scope, io = dispatcher)

    @Test
    fun `a page the site's own client gets through completes the check, closes it and stops sharing cookies`() = scope.runTest {
        val after = mutableListOf<String>()
        checks.open(SourceKey.IWARA) { after += it }
        val check = requireNotNull(checks.opened)
        gatesOpen = true

        checks.pageLoaded(check, "browser/1", letThrough = true)
        advanceUntilIdle()

        assertNull(checks.opened)
        assertEquals(listOf("browser/1"), completed)
        assertEquals(listOf("browser/1"), after)
        assertEquals(listOf("site.test", null), cookieHosts)
    }

    @Test
    fun `a refused test says so only after a page the browser got through, and only new cookies test again`() = scope.runTest {
        checks.open(target)
        val check = requireNotNull(checks.opened)

        checks.pageLoaded(check, "browser/1", letThrough = false)
        advanceUntilIdle()
        assertFalse(check.stillRefused)

        checks.pageLoaded(check, "browser/1", letThrough = true)
        advanceUntilIdle()
        assertTrue(check.stillRefused)
        assertEquals(1, tested.size)

        cookieHeader = "cf_clearance=new"
        gatesOpen = true
        checks.pageLoaded(check, "browser/1", letThrough = true)
        advanceUntilIdle()
        assertEquals(2, tested.size)
        assertNull(checks.opened)
    }

    @Test
    fun `closing by hand completes nothing, and a target without a cookie host leaves cookie sharing alone`() = scope.runTest {
        checks.open(target.copy(cookieHost = null))
        val check = requireNotNull(checks.opened)

        checks.close()
        gatesOpen = true
        checks.pageLoaded(check, "browser/1", letThrough = true)
        advanceUntilIdle()

        assertNull(checks.opened)
        assertEquals(emptyList<String>(), tested)
        assertEquals(emptyList<String?>(), cookieHosts)
    }
}
