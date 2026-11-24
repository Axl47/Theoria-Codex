package com.theoriacodex.app.vision

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameMixTest {
    @Test
    fun `a single picture keeps its own scores`() {
        val scores = floatArrayOf(0.9f, 0.4f, 0.1f)

        assertArrayEquals(scores, combineScores(listOf(scores), listOf(1)), 1e-6f)
    }

    @Test
    fun `each score is the mean of its top third of weighted pictures`() {
        val combined = combineScores(
            listOf(floatArrayOf(0.9f, 0.0f), floatArrayOf(0.1f, 0.8f), floatArrayOf(0.2f, 0.3f)),
            listOf(1, 2, 1),
        )

        assertArrayEquals(floatArrayOf(0.55f, 0.8f), combined, 1e-6f)
    }

    @Test
    fun `the embedding is the weighted mean direction at unit length`() {
        val combined = combineEmbeddings(listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 1f)), listOf(1, 1))

        assertArrayEquals(floatArrayOf(0.70710677f, 0.70710677f), combined, 1e-6f)
    }

    @Test
    fun `near copies fold into the first and flat pictures drop`() {
        val top = Pattern.TOP.bitmap()
        val left = Pattern.LEFT.bitmap()

        val distinct = distinctPictures(listOf(top, left, Pattern.LEFT.bitmap(), flat(), Pattern.QUARTERS.bitmap()))

        assertEquals(listOf(1, 2, 1), distinct.map(WeightedPicture::weight))
        assertEquals(listOf(top, left), distinct.take(2).map(WeightedPicture::bitmap))
    }

    @Test
    fun `when every picture is flat the first stands for them all`() {
        val first = flat()

        val distinct = distinctPictures(listOf(first, flat()))

        assertEquals(listOf(first to 2), distinct.map { it.bitmap to it.weight })
    }

    private fun flat(): Bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888).apply { eraseColor(GREY) }
}

private const val SIDE = 32
private const val GREY = 0xFF808080.toInt()

internal enum class Pattern(val dark: (x: Int, y: Int) -> Boolean) {
    TOP({ _, y -> y < SIDE / 2 }),
    LEFT({ x, _ -> x < SIDE / 2 }),
    QUARTERS({ x, y -> (x < SIDE / 2) == (y < SIDE / 2) }),
    ;

    fun bitmap(): Bitmap {
        val pixels = IntArray(SIDE * SIDE) { if (dark(it % SIDE, it / SIDE)) BLACK else WHITE }
        return Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        fun of(picture: Bitmap): Pattern {
            val corner = picture.getPixel(0, 0)
            return when {
                picture.getPixel(SIDE - 1, 0) == corner -> TOP
                picture.getPixel(0, SIDE - 1) == corner -> LEFT
                else -> QUARTERS
            }
        }
    }
}
