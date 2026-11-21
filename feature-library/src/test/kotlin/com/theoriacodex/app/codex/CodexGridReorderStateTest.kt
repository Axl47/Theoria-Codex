package com.theoriacodex.app.codex

import androidx.compose.foundation.lazy.grid.LazyGridState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexGridReorderStateTest {
    private val shown = listOf("a", "b", "c")

    private fun reorder() = CodexGridReorderState(LazyGridState()) { false }.apply { bind(shown, enabled = true) }

    @Test fun `a failed commit drops the order it optimistically showed`() {
        val reorder = reorder()
        reorder.stepAndCommit(shown, "a", 1) {}
        assertEquals(listOf("b", "a", "c"), reorder.order)
        reorder.commitFailed(listOf("b", "a", "c"))
        assertNull(reorder.order)
    }

    @Test fun `a failed commit keeps a newer order waiting on its own commit`() {
        val reorder = reorder()
        reorder.stepAndCommit(shown, "a", 1) {}
        reorder.stepAndCommit(shown, "a", 1) {}
        assertEquals(listOf("b", "c", "a"), reorder.order)
        reorder.commitFailed(listOf("b", "a", "c"))
        assertEquals(listOf("b", "c", "a"), reorder.order)
    }
}
