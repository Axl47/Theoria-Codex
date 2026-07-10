package com.theoriacodex.app.media

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import coil.drawable.ScaleDrawable
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.github.penfeizhou.animation.webp.WebPDrawable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimatedWebPDecoderDeviceTest {
    @Test
    fun generatedTwoFrameWebPUsesTheApiAppropriateAnimatedDecoder() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val encoded = instrumentation.context.assets
            .open("animated/two-frame.webp.b64")
            .bufferedReader()
            .use { it.readText() }
        val bytes = Base64.decode(encoded.trim(), Base64.DEFAULT)
        val context = instrumentation.targetContext

        assertTrue(isAnimatedWebPHeader(bytes))
        val normalRequest = ImageRequest.Builder(context)
            .data(bytes)
            .allowHardware(false)
            .build()
        val staticRequest = ImageRequest.Builder(context)
            .data(bytes)
            .allowHardware(false)
            .staticAnimatedWebPFrame(true)
            .build()
        assertNull(normalRequest.parameters.memoryCacheKey(ANIMATED_WEBP_DECODE_MODE_PARAMETER))
        assertEquals(
            STATIC_ANIMATED_WEBP_MEMORY_CACHE_KEY,
            staticRequest.parameters.memoryCacheKey(ANIMATED_WEBP_DECODE_MODE_PARAMETER),
        )

        val normalResult = context.imageLoader.execute(normalRequest)
        val staticResult = context.imageLoader.execute(staticRequest)
        assertTrue(normalResult is SuccessResult)
        assertTrue(staticResult is SuccessResult)

        val drawable = (normalResult as SuccessResult).drawable.unwrapScaleDrawable()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertTrue(drawable is AnimatedImageDrawable)
        } else {
            assertTrue(drawable is WebPDrawable)
            val webPDrawable = drawable as WebPDrawable
            assertEquals(2, webPDrawable.intrinsicWidth)
            assertEquals(2, webPDrawable.intrinsicHeight)
            assertEquals(2, webPDrawable.frameSeqDecoder.frameCount)
            val firstFrame = webPDrawable.frameSeqDecoder.getFrameBitmap(0)
            val secondFrame = webPDrawable.frameSeqDecoder.getFrameBitmap(1)
            assertNotEquals(firstFrame.getPixel(0, 0), secondFrame.getPixel(0, 0))
            firstFrame.recycle()
            secondFrame.recycle()
        }

        val staticDrawable = (staticResult as SuccessResult).drawable.unwrapScaleDrawable()
        assertTrue(staticDrawable is BitmapDrawable)
        val firstFrame = (staticDrawable as BitmapDrawable).bitmap
        assertEquals(2, firstFrame.width)
        assertEquals(2, firstFrame.height)
    }

    private tailrec fun Drawable.unwrapScaleDrawable(): Drawable {
        return if (this is ScaleDrawable) child.unwrapScaleDrawable() else this
    }
}
