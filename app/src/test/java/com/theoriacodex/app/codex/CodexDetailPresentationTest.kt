package com.theoriacodex.app.codex

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexDetailPresentationTest {
    @Test
    fun `item summary exposes visible count only while filters are active`() {
        assertEquals("8 items", codexItemSummary(8, 8, filtersActive = false))
        assertEquals("3 of 8 items", codexItemSummary(3, 8, filtersActive = true))
        assertEquals("0 of 8 items", codexItemSummary(0, 8, filtersActive = true))
    }
}
