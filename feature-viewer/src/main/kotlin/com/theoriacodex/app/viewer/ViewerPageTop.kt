package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import coil.size.Dimension
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object PageTopCrop : Transformation {
    override val cacheKey = "viewer-page-top"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = size.width
        val height = size.height
        val shape = if (width is Dimension.Pixels && height is Dimension.Pixels) height.px.toFloat() / width.px else 1f
        val window = (input.width * shape).roundToInt()
        if (window <= 0 || window >= input.height) return input
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return Bitmap.createBitmap(input, 0, pageTopStart(pixels, input.width, input.height, window), input.width, window)
    }
}

internal fun pageTopStart(pixels: IntArray, width: Int, height: Int, window: Int): Int {
    val step = max(1, width / ROW_SAMPLES)
    val samples = (width + step - 1) / step
    val top = (0 until width step step).map { pixels[it] }
    val background = top.maxBy { colour -> top.count { !differs(it, colour) } }
    val needed = max(min(2, samples), samples / CONTENT_SHARE)
    val content = (0 until height).firstOrNull { y ->
        (0 until width step step).count { x -> differs(pixels[y * width + x], background) } >= needed
    } ?: return 0
    return (content - width / MARGIN_FRACTION).coerceIn(0, height - window)
}

private fun differs(a: Int, b: Int) = CHANNEL_SHIFTS.any { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) > CHANNEL_TOLERANCE }

private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

private const val ROW_SAMPLES = 64

private const val CONTENT_SHARE = 32

private const val CHANNEL_TOLERANCE = 40

private const val MARGIN_FRACTION = 16
