package com.theoriacodex.app.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stores a picture embedding in [bytesFor] bytes: one signed byte per dimension, scaled so the largest magnitude is 127,
 * followed by that scale as a little-endian float. The spike measured int8 storage of WD-ViT embeddings at cosine
 * 0.9997 to float, and it keeps 10,000 library posts under 8 MB.
 */
object PictureEmbedding {
    fun bytesFor(dimensions: Int): Int = dimensions + Float.SIZE_BYTES

    fun encode(values: FloatArray): ByteArray {
        val largest = values.maxOfOrNull(::abs) ?: 0f
        val scale = if (largest == 0f) 1f else largest / MAX
        val out = ByteBuffer.allocate(bytesFor(values.size)).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { out.put((it / scale).roundToInt().coerceIn(-MAX.toInt(), MAX.toInt()).toByte()) }
        out.putFloat(scale)
        return out.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size > Float.SIZE_BYTES) { "embedding of ${bytes.size} bytes" }
        val dimensions = bytes.size - Float.SIZE_BYTES
        val scale = ByteBuffer.wrap(bytes, dimensions, Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).float
        return FloatArray(dimensions) { bytes[it] * scale }
    }

    private const val MAX = 127f
}
