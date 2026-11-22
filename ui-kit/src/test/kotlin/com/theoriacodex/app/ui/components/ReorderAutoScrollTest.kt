package com.theoriacodex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderAutoScrollTest {
    @Test fun `auto scroll accelerates into each edge and rests in the middle`() {
        fun step(top: Float) = reorderAutoScrollStep(top, top + 100f, 0f, 1000f, edgeZone = 100f, maxStep = 10f)
        assertEquals(0f, step(400f), 0.001f)
        assertEquals(-5f, step(50f), 0.001f)
        assertEquals(-10f, step(-40f), 0.001f)
        assertEquals(5f, step(850f), 0.001f)
        assertEquals(10f, step(950f), 0.001f)
    }
}
