package com.theoriacodex.app.vision

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows

class PictureEmbeddingTest {
    @Test
    fun `round trip stays within one step of the largest magnitude and size is dimensions plus the scale`() {
        val random = Random(7)
        val values = FloatArray(768) { random.nextFloat() * 2f - 1f }
        values[10] = -1.5f

        val bytes = PictureEmbedding.encode(values)
        val decoded = PictureEmbedding.decode(bytes)

        assertEquals(768 + 4, bytes.size)
        assertEquals(PictureEmbedding.bytesFor(768), bytes.size)
        assertEquals(values.size, decoded.size)
        val tolerance = 1.5f / 127f
        values.indices.forEach { assertTrue("dimension $it", abs(values[it] - decoded[it]) <= tolerance) }
        assertEquals(-1.5f, decoded[10], 1e-5f)
    }

    @Test
    fun `an all-zero vector survives`() {
        val decoded = PictureEmbedding.decode(PictureEmbedding.encode(FloatArray(16)))

        assertArrayEquals(FloatArray(16), decoded, 0f)
    }

    @Test
    fun `decode rejects bytes with no room for a dimension`() {
        assertThrows(IllegalArgumentException::class.java) { PictureEmbedding.decode(ByteArray(4)) }
    }
}
