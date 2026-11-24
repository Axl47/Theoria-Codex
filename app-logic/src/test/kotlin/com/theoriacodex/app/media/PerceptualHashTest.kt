package com.theoriacodex.app.media

import com.theoriacodex.domain.model.isSamePicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {
    @Test fun `matches imagehash phash on fixed grey arrays (ADR 0075)`() {
        assertEquals("94294a5569baeb56", hex(PerceptualHash.ofLuma(grey { x, y -> (x * 7 + y * 3) % 256 })))
        assertEquals("ec9da8b74e90a89a", hex(PerceptualHash.ofLuma(noise(seed = 42))))
        assertEquals("893636c13ece303f", hex(PerceptualHash.ofLuma(disc())))
    }

    @Test fun `a solid or nearly solid picture has no hash (ADR 0006, 0075)`() {
        assertNull(PerceptualHash.ofLuma(DoubleArray(32 * 32) { 128.0 }))
        assertNull(PerceptualHash.ofArgb(IntArray(40 * 30) { 0xFF336699.toInt() }, 40, 30))
    }

    @Test fun `pixels averaged down to the grid hash like the grid itself`() {
        val luma = disc()
        val pixels = IntArray(96 * 96) { index ->
            val level = luma[(index / 96 / 3) * 32 + (index % 96) / 3].toInt()
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }

        assertEquals(PerceptualHash.ofLuma(luma), PerceptualHash.ofArgb(pixels, 96, 96))
    }

    @Test fun `a smaller copy of a picture stays the same picture`() {
        val full = smooth(width = 300, height = 200)
        val thumbnail = smooth(width = 150, height = 100)

        assertTrue(isSamePicture(PerceptualHash.ofArgb(full, 300, 200)!!, PerceptualHash.ofArgb(thumbnail, 150, 100)!!))
    }

    @Test fun `coloured pixels keep the hash stored for them, from 601 grey weights and box averaging (ADR 0075)`() {
        val pixels = IntArray(64 * 48) { index ->
            val x = index % 64
            val y = index / 64
            val red = if ((x - 20) * (x - 20) + (y - 16) * (y - 16) < 100) 255 else 0
            val blue = if ((x - 44) * (x - 44) + (y - 32) * (y - 32) < 100) 255 else 0
            (0xFF shl 24) or (red shl 16) or ((x * 3) shl 8) or blue
        }
        assertEquals("86c738393bcc78b8", hex(PerceptualHash.ofArgb(pixels, 64, 48)))
    }

    private fun hex(hash: Long?) = "%016x".format(hash)

    private fun grey(level: (x: Int, y: Int) -> Int) = DoubleArray(32 * 32) { index -> level(index % 32, index / 32).toDouble() }

    private fun disc() = grey { x, y -> minOf(255, (if ((x - 12) * (x - 12) + (y - 20) * (y - 20) < 64) 230 else 30) + x * 2) }

    private fun noise(seed: Long): DoubleArray {
        var state = seed
        return DoubleArray(32 * 32) {
            state = (state * 1_103_515_245 + 12_345) % (1L shl 31)
            ((state shr 16) and 0xFF).toDouble()
        }
    }

    private fun smooth(width: Int, height: Int) = IntArray(width * height) { index ->
        val x = (index % width).toDouble() / width
        val y = (index / width).toDouble() / height
        val level = (127 + 100 * kotlin.math.sin(x * 7) * kotlin.math.cos(y * 5) + 25 * x).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (level shl 16) or ((255 - level) shl 8) or (level / 2)
    }
}
