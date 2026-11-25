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
        chrome.atTop = { true }
        assertEquals(0f, chrome.settleTarget())
    }

    @Test fun `the lowest hideable sets the range and an inactive chrome never hides (ADR 0218)`() {
        val chrome = state().apply { record("chips", 160) }
        assertEquals(160, chrome.hideablePx)
        chrome.active = false
        assertEquals(0f, chrome.consume(-50f))
        assertEquals(0, chrome.hideablePx)
    }

    @Test fun `at the top the list and pull to refresh take a downward scroll before the header hides (ADR 0218)`() {
        val chrome = state().apply { atTop = { true } }
        assertEquals(0f, chrome.preScroll(-30f))
        assertEquals(-30f, chrome.postScroll(-30f))
        assertEquals(30f, chrome.offsetPx)
        assertEquals(0f, chrome.postScroll(30f))
    }

    @Test fun `show returns the header at once (ADR 0218)`() {
        val chrome = state()
        chrome.consume(-70f)
        chrome.show()
        assertEquals(0f, chrome.offsetPx)
    }
}
