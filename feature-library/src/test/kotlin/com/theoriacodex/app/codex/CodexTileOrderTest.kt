package com.theoriacodex.app.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexTileOrderTest {
    private val order = listOf("a", "b", "c", "d")

    @Test fun `a dragged tile takes the target's place and the tiles between shift toward its old place`() {
        assertEquals(listOf("b", "c", "a", "d"), moveTile(order, "a", "c", emptySet()))
        assertEquals(listOf("d", "a", "b", "c"), moveTile(order, "d", "a", emptySet()))
    }

    @Test fun `fixed tiles keep their places and are skipped over`() {
        val withUndo = listOf("a", "undo", "b", "c")
        assertEquals(listOf("b", "undo", "c", "a"), moveTile(withUndo, "a", "c", setOf("undo")))
        assertEquals(withUndo, moveTile(withUndo, "a", "undo", setOf("undo")))
        assertEquals(withUndo, moveTile(withUndo, "undo", "c", setOf("undo")))
    }

    @Test fun `unknown or identical keys leave the order alone`() {
        assertEquals(order, moveTile(order, "a", "a", emptySet()))
        assertEquals(order, moveTile(order, "missing", "b", emptySet()))
    }

    @Test fun `steps move one movable place and stop at either end`() {
        val withUndo = listOf("a", "undo", "b")
        assertEquals(listOf("b", "undo", "a"), stepTile(withUndo, "a", 1, setOf("undo")))
        assertEquals(listOf("b", "undo", "a"), stepTile(withUndo, "b", -1, setOf("undo")))
        assertNull(stepTile(withUndo, "a", -1, setOf("undo")))
        assertNull(stepTile(withUndo, "b", 1, setOf("undo")))
        assertNull(stepTile(withUndo, "undo", 1, setOf("undo")))
    }
}
