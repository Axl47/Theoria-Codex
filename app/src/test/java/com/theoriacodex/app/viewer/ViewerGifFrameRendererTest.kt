package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerGifFrameRendererTest {
    @Test
    fun `renders the requested position and alternates between two buffers`() {
        val renderer = twoFrameRenderer()

        renderer.request(0)
        val red = awaitFrame(renderer, Color.RED)
        renderer.request(BLUE_MS)
        val blue = awaitFrame(renderer, Color.BLUE)
        renderer.request(0)
        val redAgain = awaitFrame(renderer, Color.RED)

        assertNotSame(red, blue)
        assertSame(red, redAgain)
        assertEquals(Color.BLUE, blue.getPixel(0, 0)) // Red was drawn into the spare buffer, not the one on screen.
    }

    @Test
    fun `settles on the newest of several requests`() {
        val renderer = twoFrameRenderer()

        repeat(10) { renderer.request(if (it % 2 == 0) 0 else BLUE_MS) }
        renderer.request(BLUE_MS)

        awaitFrame(renderer, Color.BLUE)
    }

    @Test
    fun `close drops the frame and ignores renders still in flight`() {
        val renderer = twoFrameRenderer()
        renderer.request(0)
        awaitFrame(renderer, Color.RED)

        renderer.request(BLUE_MS)
        renderer.close()
        repeat(20) { idleMain(); Thread.sleep(5) }

        assertNull(renderer.frame)
    }

    /** Idles the main looper (where publishes land) until the shown frame's pixels are [color]. */
    private fun awaitFrame(renderer: ViewerGifFrameRenderer, color: Int): Bitmap {
        repeat(400) {
            idleMain()
            renderer.frame?.asAndroidBitmap()?.takeIf { it.getPixel(0, 0) == color }?.let { return it }
            Thread.sleep(5)
        }
        error("No frame of color ${Integer.toHexString(color)} was published")
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // A 4x4 animation that is red for its first 100 ms and blue after.
    private fun twoFrameRenderer() = ViewerGifFrameRenderer(4, 4) { canvas, positionMs ->
        canvas.drawColor(if (positionMs < 100) Color.RED else Color.BLUE)
    }

    private companion object {
        const val BLUE_MS = 150
    }
}
