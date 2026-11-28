package com.theoriacodex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PullRefreshTest {
    @Test fun `a pull arms only past the threshold (ADR 0219)`() {
        assertEquals(listOf(false, false, true), listOf(0.6f, 1f, 1.2f).map(::isPullArmed))
    }
}
