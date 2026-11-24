package com.theoriacodex.app.vision

import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows

class PicturePreprocessorTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `argbToBgrPlanes writes blue, green then red planes scaled to -1 and 1`() {
        val pixels = intArrayOf(argb(255, r = 255, g = 0, b = 51), argb(0x80, r = 0, g = 255, b = 0))
        val out = FloatArray(6)

        argbToBgrPlanes(pixels, out)

        assertArrayEquals(floatArrayOf(-0.6f, -1f, -1f, 1f, 1f, -1f), out, 1e-6f)
    }

    @Test
    fun `argbToBgrPlanes refuses an output of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) { argbToBgrPlanes(IntArray(2), FloatArray(5)) }
    }
}
