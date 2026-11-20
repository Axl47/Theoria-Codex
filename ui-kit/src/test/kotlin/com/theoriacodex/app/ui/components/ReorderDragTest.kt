package com.theoriacodex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderDragTest {
    private fun dragging() = ReorderListState(listOf("a", "b", "c")) { it }.apply {
        reorderMode = true
        itemHeightPx = 100f
        itemSpacingPx = 10f
        startDrag("a", 0)
    }

    @Test fun `crossing a row reports the step and keeps the row under the finger across the gap`() {
        val state = dragging()
        assertFalse(state.drag("a", 0, 50f))
        assertTrue(state.drag("a", 0, 10f))
        assertEquals(listOf("b", "a", "c"), state.reorderDraft)
        // 60 dragged, less one 110 layout step: the row's visual position has not jumped.
        assertEquals(-50f, state.dragOffsetY, 0.001f)
    }

    @Test fun `the ends of the list do not report steps`() {
        val state = dragging()
        assertFalse(state.drag("a", 0, -500f))
        assertEquals(listOf("a", "b", "c"), state.reorderDraft)
    }

    @Test fun `auto scroll accelerates into each edge and rests in the middle`() {
        fun step(top: Float) = reorderAutoScrollStep(top, top + 100f, 0f, 1000f, edgeZone = 100f, maxStep = 10f)
        assertEquals(0f, step(400f), 0.001f)
        assertEquals(-5f, step(50f), 0.001f)
        assertEquals(-10f, step(-40f), 0.001f)
        assertEquals(5f, step(850f), 0.001f)
        assertEquals(10f, step(950f), 0.001f)
    }
}
