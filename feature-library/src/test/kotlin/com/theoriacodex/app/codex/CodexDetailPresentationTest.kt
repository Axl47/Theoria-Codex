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

    @Test
    fun `item summary exposes selected over total while bulk editing`() {
        assertEquals("0/8", codexItemSummary(3, 8, filtersActive = true, selectedCount = 0))
        assertEquals("3/8", codexItemSummary(8, 8, filtersActive = false, selectedCount = 3))
    }

    @Test
    fun `bulk action descriptions use the selected post count`() {
        assertEquals(
            "Remove 1 selected post from this Codex",
            codexSelectedPostActionDescription("Remove", 1, "from this Codex"),
        )
        assertEquals(
            "Add 3 selected posts to another Codex",
            codexSelectedPostActionDescription("Add", 3, "to another Codex"),
        )
    }
}
