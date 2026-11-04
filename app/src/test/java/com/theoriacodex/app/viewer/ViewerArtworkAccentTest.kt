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

    @Test fun `hair and clothes set the accent even when skin fills most of the art`() {
        val skin = 0xFFF0C8A8.toInt()
        val blueHair = 0xFF3050E0.toInt()
        val greenDress = 0xFF20B050.toInt()
        val blue = requireNotNull(viewerAccentFromPixels(IntArray(100) { if (it < 15) blueHair else skin }))
        val green = requireNotNull(viewerAccentFromPixels(IntArray(100) { if (it < 15) greenDress else skin }))
        assertTrue("blue hair should win, got ${Integer.toHexString(blue)}", channel(blue, 0) > channel(blue, 16))
        assertTrue("green dress should win, got ${Integer.toHexString(green)}", channel(green, 8) > channel(green, 16))
    }

    @Test fun `art that is only skin still gets a warm accent`() {
        val accent = requireNotNull(viewerAccentFromPixels(IntArray(100) { 0xFFF0C8A8.toInt() }))
        assertTrue(channel(accent, 16) > channel(accent, 0))
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
