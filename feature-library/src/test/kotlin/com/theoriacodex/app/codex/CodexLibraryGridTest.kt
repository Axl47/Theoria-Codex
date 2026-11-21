package com.theoriacodex.app.codex

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexLibraryGridTest {
    @Test fun `the remembered order keeps a deleted Codex until the order changes otherwise`() {
        val shown = listOf("a", "b", "c")
        assertEquals(shown, rememberedTileOrder(shown, listOf("a", "c")))
        assertEquals(listOf("c", "a"), rememberedTileOrder(shown, listOf("c", "a")))
        assertEquals(listOf("a", "b", "c", "d"), rememberedTileOrder(shown, listOf("a", "b", "c", "d")))
    }

    @Test fun `the vacated cell goes after the Codices that preceded it`() {
        val shown = listOf("a", "b", "c", "d")
        assertEquals(1, vacatedTileIndex(shown, listOf("a", "c", "d"), "b"))
        assertEquals(0, vacatedTileIndex(shown, listOf("b", "c", "d"), "a"))
        assertEquals(2, vacatedTileIndex(shown, listOf("b", "c"), "d"))
        assertEquals(0, vacatedTileIndex(shown, listOf("a"), "unknown"))
    }
}
