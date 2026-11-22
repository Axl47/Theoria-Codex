package com.theoriacodex.visionbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** How a model wants its input: square side, how to fit the picture, and how to turn pixels into floats. */
enum class ModelKind(val side: Int) {
    /** WD tagger v3: pad to a white square, resize 448, BGR, scale to [-1, 1]. */
    WD(448) {
        override fun fit(source: Bitmap, canvas: Canvas) {
            val longest = maxOf(source.width, source.height).toFloat()
            val scale = side / longest
            val left = (longest - source.width) / 2f * scale
            val top = (longest - source.height) / 2f * scale
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, null, RectF(left, top, left + source.width * scale, top + source.height * scale), FILTER)
        }

        override fun toFloats(pixels: IntArray, out: FloatArray) {
            val plane = pixels.size
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i] = (p and 0xFF) / 127.5f - 1f
                out[plane + i] = (p shr 8 and 0xFF) / 127.5f - 1f
                out[2 * plane + i] = (p shr 16 and 0xFF) / 127.5f - 1f
            }
        }
    },

    /** DINOv2-small (HF processor): shortest side 256, center crop 224, RGB, ImageNet mean/std. */
    DINO(224) {
        override fun fit(source: Bitmap, canvas: Canvas) {
            val scale = 256f / minOf(source.width, source.height)
            val width = Math.round(source.width * scale).toFloat()
            val height = Math.round(source.height * scale).toFloat()
            val left = -((width - side).toInt() / 2).toFloat()
            val top = -((height - side).toInt() / 2).toFloat()
            canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), FILTER)
        }

        override fun toFloats(pixels: IntArray, out: FloatArray) {
            val plane = pixels.size
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i] = ((p shr 16 and 0xFF) / 255f - 0.485f) / 0.229f
                out[plane + i] = ((p shr 8 and 0xFF) / 255f - 0.456f) / 0.224f
                out[2 * plane + i] = ((p and 0xFF) / 255f - 0.406f) / 0.225f
            }
        }
    };

    /** Draws [source] onto a [side]-square canvas the way this model's host preprocessing does. */
    abstract fun fit(source: Bitmap, canvas: Canvas)

    /** Converts ARGB pixels of a [side]-square image to the model's NCHW float input. */
    abstract fun toFloats(pixels: IntArray, out: FloatArray)

    companion object {
        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}

/** Reusable buffers for turning one decoded image into a model input without per-image allocation. */
class Preprocessor(private val kind: ModelKind) {
    private val square = Bitmap.createBitmap(kind.side, kind.side, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val pixels = IntArray(kind.side * kind.side)
    val input = FloatArray(3 * kind.side * kind.side)

    /** Full product path: fit a decoded thumbnail, then convert. */
    fun fromThumbnail(bitmap: Bitmap): FloatArray {
        kind.fit(bitmap, canvas)
        square.getPixels(pixels, 0, kind.side, 0, 0, kind.side, kind.side)
        kind.toFloats(pixels, input)
        return input
    }

    /** Parity path: [bitmap] is already the host-preprocessed square, so only convert. */
    fun fromPrepared(bitmap: Bitmap): FloatArray {
        require(bitmap.width == kind.side && bitmap.height == kind.side) { "prepared input must be ${kind.side} px" }
        bitmap.getPixels(pixels, 0, kind.side, 0, 0, kind.side, kind.side)
        kind.toFloats(pixels, input)
        return input
    }
}
