package com.theoriacodex.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Turns a decoded preview into WD tagger v3's input: centred on a white square, resized to [SIDE] px (bilinear), BGR
 * channel planes (NCHW) scaled to [-1, 1]. Phase 0 measured this phone-side resize against the host's bicubic at about
 * 0.004 cosine. Reuses its buffers, so one instance serves one picture at a time.
 */
internal class PicturePreprocessor {
    private val square = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val pixels = IntArray(SIDE * SIDE)
    private val input = FloatArray(INPUT_SIZE)

    /** The model input for [picture]; the returned array is reused by the next call. */
    fun input(picture: Bitmap): FloatArray {
        val longest = maxOf(picture.width, picture.height).toFloat()
        val scale = SIDE / longest
        val left = (longest - picture.width) / 2f * scale
        val top = (longest - picture.height) / 2f * scale
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(picture, null, RectF(left, top, left + picture.width * scale, top + picture.height * scale), FILTER)
        square.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        argbToBgrPlanes(pixels, input)
        return input
    }

    companion object {
        const val SIDE = 448
        const val INPUT_SIZE = 3 * SIDE * SIDE
        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/** Writes [pixels] (ARGB) into [out] as blue, green, then red planes, each value scaled from 0..255 to [-1, 1]. */
internal fun argbToBgrPlanes(pixels: IntArray, out: FloatArray) {
    val plane = pixels.size
    require(out.size == 3 * plane) { "expected ${3 * plane} floats, got ${out.size}" }
    for (i in pixels.indices) {
        val p = pixels[i]
        out[i] = (p and 0xFF) / HALF - 1f
        out[plane + i] = (p shr 8 and 0xFF) / HALF - 1f
        out[2 * plane + i] = (p shr 16 and 0xFF) / HALF - 1f
    }
}

private const val HALF = 127.5f
