package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPageTopTest {
    private val white = 0xFFFFFFFF.toInt()
    private val ink = 0xFF202020.toInt()

    private fun page(width: Int, height: Int, contentFrom: Int) =
        IntArray(width * height) { index -> if (index / width >= contentFrom) ink else white }

    @Test fun `a long page's crop starts a little above its first content`() {
        assertEquals(400 - 64 / 16, pageTopStart(page(64, 2000, contentFrom = 400), 64, 2000, window = 64))
    }

    @Test fun `a page inked from its first row keeps its top`() {
        assertEquals(0, pageTopStart(page(64, 2000, contentFrom = 0), 64, 2000, window = 64))
    }

    @Test fun `content below the last full window keeps that window, and a blank page its top`() {
        assertEquals(2000 - 64, pageTopStart(page(64, 2000, contentFrom = 1990), 64, 2000, window = 64))
        assertEquals(0, pageTopStart(page(64, 2000, contentFrom = 2000), 64, 2000, window = 64))
    }

    @Test fun `a speck or faint noise in the margin is not content`() {
        val pixels = page(64, 2000, contentFrom = 400)
        pixels[0] = ink
        pixels[100 * 64 + 10] = ink
        pixels[200 * 64 + 5] = 0xFFF0F0F0.toInt()
        pixels[200 * 64 + 6] = 0xFFE8E8E8.toInt()
        assertEquals(400 - 4, pageTopStart(pixels, 64, 2000, window = 64))
        val narrow = page(32, 1000, contentFrom = 320).apply { this[0] = ink }
        assertEquals(320 - 2, pageTopStart(narrow, 32, 1000, window = 32))
    }
}
