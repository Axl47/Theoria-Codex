package com.theoriacodex.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineNameFieldTest {
    @Test
    fun `a rename commits a trimmed changed name only`() {
        assertEquals("Skies", committedName("Sky", "  Skies "))
        assertEquals(null, committedName("Sky", " Sky "))
        assertEquals(null, committedName("Sky", "   "))
    }
}
