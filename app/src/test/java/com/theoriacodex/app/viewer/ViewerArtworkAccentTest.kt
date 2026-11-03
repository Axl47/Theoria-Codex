package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerArtworkAccentTest {
    private val opaqueGray = 0xFF808080.toInt()
    private val opaqueRed = 0xFFD02020.toInt()
    private val opaqueBlue = 0xFF2030D0.toInt()

    @Test fun `grayscale art keeps the white fallback`() {
        assertNull(viewerAccentFromPixels(IntArray(100) { opaqueGray }))
    }

    @Test fun `a sliver of color in monochrome art does not tint it`() {
        assertNull(viewerAccentFromPixels(IntArray(100) { if (it < 3) opaqueRed else opaqueGray }))
    }

    @Test fun `the most prominent hue wins`() {
        val accent = requireNotNull(viewerAccentFromPixels(IntArray(100) { if (it < 70) opaqueBlue else opaqueRed }))
        assertTrue("expected blue-dominant, got ${Integer.toHexString(accent)}", channel(accent, 0) > channel(accent, 16))
    }

    @Test fun `dark and pale art of one hue give the same legible accent`() {
        val dark = viewerAccentFromPixels(IntArray(100) { 0xFF601010.toInt() })
        val pale = viewerAccentFromPixels(IntArray(100) { 0xFFFFA0A0.toInt() })
        assertNotNull(dark)
        assertEquals(dark, pale)
        assertTrue("accent must stay bright over the scrim", channel(requireNotNull(dark), 16) > 200)
    }

    private fun channel(color: Int, shift: Int) = color shr shift and 0xFF
}
