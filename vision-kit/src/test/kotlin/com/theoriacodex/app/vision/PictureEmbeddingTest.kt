package com.theoriacodex.app.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureEmbeddingTest {
    @Test
    fun `round trip stays within one step of the largest magnitude and size is dimensions plus the scale (ADR 0096)`() {
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
    fun `encoding is one scaled byte per dimension, then the scale as a little-endian float (ADR 0096)`() {
        val scale = ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(1.27f / 127f).array()

        assertArrayEquals(byteArrayOf(127, -50, 0) + scale, PictureEmbedding.encode(floatArrayOf(1.27f, -0.5f, 0f)))
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
