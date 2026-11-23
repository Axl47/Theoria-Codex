package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import coil.size.Dimension
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Crops a page to its thumbnail's shape from the top, past the blank margin a page usually opens with, so a long strip
 * shows where it starts rather than a slice of its middle. A page no taller than that shape is left to the tile's crop.
 */
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

/**
 * The first row of a [window]-row crop of a [width]×[height] page (ARGB pixels, row by row) that starts a little above
 * its first row of content: the first that differs from the top-left pixel's colour across enough of its width. A page
 * with no content keeps its top; content starting lower than the last full window keeps that window.
 */
internal fun pageTopStart(pixels: IntArray, width: Int, height: Int, window: Int): Int {
    val background = pixels[0]
    val step = max(1, width / ROW_SAMPLES)
    val samples = (width + step - 1) / step
    val needed = max(1, samples / CONTENT_SHARE)
    val content = (0 until height).firstOrNull { y ->
        (0 until width step step).count { x -> differs(pixels[y * width + x], background) } >= needed
    } ?: return 0
    return (content - width / MARGIN_FRACTION).coerceIn(0, height - window)
}

private fun differs(a: Int, b: Int) = CHANNEL_SHIFTS.any { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) > CHANNEL_TOLERANCE }

private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

/** Pixels compared across a row. */
private const val ROW_SAMPLES = 64

/** A row is content once one in this many of its samples differs (two of 64), so a lone speck stays blank. */
private const val CONTENT_SHARE = 32

/** Beyond JPEG noise on a flat margin. */
private const val CHANNEL_TOLERANCE = 40

/** How much of the page's width is kept above its first content, so it does not start flush with the tile's edge. */
private const val MARGIN_FRACTION = 16
