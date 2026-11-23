package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** A 3000 px page in a 1000 px screen; the status sits [REACH] inside the page's edges. */
class VisiblePartShiftTest {
    /** Where the status's middle lands on screen with the page's top at [pageOffset]. */
    private fun statusOnScreen(pageOffset: Int, pageSize: Int = 3000): Float =
        pageOffset + pageSize / 2f + visiblePartShift(pageOffset, pageSize, viewportStart = 0, viewportEnd = 1000, reach = REACH)

    @Test fun `a page coming in at the bottom shows its status in the part on screen`() {
        assertEquals(800f, statusOnScreen(pageOffset = 600))
    }

    @Test fun `a page filling the screen keeps its status in the screen's middle`() {
        assertEquals(500f, statusOnScreen(pageOffset = -200))
        assertEquals(500f, statusOnScreen(pageOffset = -1500))
    }

    @Test fun `a page leaving at the top takes its status along`() {
        assertEquals(200f, statusOnScreen(pageOffset = -2600))
    }

    @Test fun `the status never passes the page's edges`() {
        assertEquals(990f, statusOnScreen(pageOffset = 950))
        assertEquals(10f, statusOnScreen(pageOffset = -2950))
    }

    @Test fun `a page too short for the status leaves it in the middle`() {
        assertEquals(0f, visiblePartShift(pageOffset = 900, pageSize = 70, viewportStart = 0, viewportEnd = 1000, reach = REACH))
    }

    private companion object {
        const val REACH = 40f
    }
}
