package com.theoriacodex.sources.nhentai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class NhentaiTitleTest {
    @Test
    fun `removes annotations anywhere in a title and normalizes whitespace`() {
        assertEquals(
            "A Gallery Title",
            cleanNhentaiTitle(" [Artist] A (extra) Gallery [translation] Title (English) "),
        )
        assertEquals("Gallery", cleanNhentaiTitle("[Group (Artist)] Gallery (part (extra))"))
    }

    @Test
    fun `removes curly brace annotations including nested annotations`() {
        assertEquals("A Gallery Title", cleanNhentaiTitle("{Group} A {extra} Gallery Title {English}"))
        assertEquals("Gallery", cleanNhentaiTitle("{Group {Artist}} Gallery {part (extra)}"))
        assertNull(cleanNhentaiTitle("{English}"))
        assertEquals("Gallery {unfinished", cleanNhentaiTitle("Gallery {unfinished"))
    }

    @Test
    fun `empty titles and titles consisting only of annotations have no title`() {
        assertNull(cleanNhentaiTitle(null))
        assertNull(cleanNhentaiTitle("   "))
        assertNull(cleanNhentaiTitle("[Artist] (English)"))
    }

    @Test
    fun `preserves ordinary titles and unmatched brackets`() {
        assertEquals("Gallery Title", cleanNhentaiTitle("Gallery Title"))
        assertEquals("Gallery (unfinished", cleanNhentaiTitle("Gallery (unfinished"))
    }
}
