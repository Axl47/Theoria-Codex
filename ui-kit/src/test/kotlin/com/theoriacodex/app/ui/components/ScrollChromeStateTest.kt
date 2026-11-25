package com.theoriacodex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollChromeStateTest {
    private fun state(header: Int = 100) = ScrollChromeState().apply { record("header", header) }

    @Test fun `scrolling down hides the header with the finger and stops at its height (ADR 0218)`() {
        val chrome = state()
        assertEquals(-40f, chrome.consume(-40f))
        assertEquals(40f, chrome.offsetPx)
        assertEquals(-60f, chrome.consume(-90f))
        assertEquals(100f, chrome.offsetPx)
        assertEquals(0f, chrome.consume(-30f))
    }

    @Test fun `any scroll up reveals before the list moves (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-100f)
        assertEquals(10f, chrome.consume(10f))
        assertEquals(90f, chrome.offsetPx)
        assertEquals(0f, chrome.settleTarget())
    }

    @Test fun `release settles toward the last direction but never hides at the top (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-20f)
        assertEquals(100f, chrome.settleTarget())
        chrome.listAtStart = { true }
        assertEquals(0f, chrome.settleTarget())
    }

    @Test fun `the lowest hideable sets the range and an inactive chrome never hides (ADR 0218)`() {
        val chrome = state().apply { record("chips", 160) }
        assertEquals(160, chrome.hideablePx)
        chrome.active = false
        assertEquals(0f, chrome.consume(-50f))
        assertEquals(0, chrome.hideablePx)
    }

    @Test fun `at the top a downward scroll goes to the list and pull to refresh, never the header (ADR 0218)`() {
        val chrome = state().apply { listAtStart = { true } }
        assertEquals(0f, chrome.preScroll(-30f))
        assertEquals(0f, chrome.offsetPx)
        chrome.listAtStart = { false }
        assertEquals(-30f, chrome.preScroll(-30f))
    }

    @Test fun `a list that fits only once the header hides keeps it hidden at its end (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-50f)
        chrome.listAtStart = { true }
        chrome.listAtEnd = { true }
        assertEquals(-40f, chrome.preScroll(-40f))
        assertEquals(100f, chrome.settleTarget())
        chrome.show()
        assertEquals(0f, chrome.preScroll(-40f))
    }

    @Test fun `a list that fits even with the header shown is at its top, so a hidden header returns (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-100f)
        chrome.listAtStart = { true }
        chrome.listAtEnd = { true }
        chrome.listEndGap = { 100 }
        assertEquals(0f, chrome.preScroll(-40f))
        assertEquals(0f, chrome.settleTarget())
    }

    @Test fun `a focused header control keeps the header shown (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-60f)
        chrome.focus("settings", hasFocus = true)
        assertEquals(0f, chrome.offsetPx)
        assertEquals(0f, chrome.consume(-50f))
        chrome.focus("settings", hasFocus = false)
        assertEquals(-50f, chrome.consume(-50f))
    }

    @Test fun `show returns the header at once (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-70f)
        chrome.show()
        assertEquals(0f, chrome.offsetPx)
    }
}
