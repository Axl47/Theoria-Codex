package com.theoriacodex.app.media

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import coil.request.Parameters
import coil.size.Scale
import coil.size.Size
import com.github.penfeizhou.animation.webp.WebPDrawable
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedWebPDecodeIntegrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val bytes get() = Base64.getMimeDecoder().decode(File("src/androidTest/assets/animated/two-frame.webp.b64").readText())

    @Test fun `overview decodes a sampled still frame and viewer retains both controllable frames`() = runBlocking {
        val loader = ImageLoader(context)
        try {
            for (mode in listOf(AnimatedWebPDecodeMode.STATIC_FIRST_FRAME, AnimatedWebPDecodeMode.CONTROLLABLE)) {
                ImageSource(Buffer().write(bytes), context).use { source ->
                    val options = Options(context, size = Size(1, 1), scale = Scale.FIT,
                        parameters = Parameters.Builder().set(ANIMATED_WEBP_DECODE_MODE_PARAMETER, mode).build())
                    val decoder = requireNotNull(AnimatedWebPDecoder.Factory().create(
                        SourceResult(source, "image/webp", DataSource.MEMORY), options, loader,
                    ))
                    val decoded = requireNotNull(decoder.decode())
                    when (mode) {
                        AnimatedWebPDecodeMode.STATIC_FIRST_FRAME -> {
                            val bitmap = (decoded.drawable as BitmapDrawable).bitmap
                            assertEquals(1, bitmap.width)
                            assertEquals(1, bitmap.height)
                            assertTrue(decoded.isSampled)
                            bitmap.recycle()
                        }
                        else -> {
                            val drawable = decoded.drawable as WebPDrawable
                            assertEquals(2, drawable.frameSeqDecoder.frameCount)
                            assertEquals(2, drawable.intrinsicWidth)
                            drawable.stop()
                        }
                    }
                }
            }
        } finally { loader.shutdown() }
    }

    @Test fun `ordinary animation delegates to the platform decoder`() {
        val loader = ImageLoader(context)
        try {
            ImageSource(Buffer().write(bytes), context).use { source ->
                assertNull(AnimatedWebPDecoder.Factory().create(
                    SourceResult(source, "image/webp", DataSource.MEMORY), Options(context), loader,
                ))
            }
        } finally { loader.shutdown() }
    }

    @Test fun `changed dimensions are rejected before allocating a frame`() = runBlocking {
        ImageSource(Buffer().write(bytes), context).use { source ->
            val decoder = AnimatedWebPDecoder(source, Options(context), AnimatedWebPDecodeMode.STATIC_FIRST_FRAME,
                AnimatedWebPHeader(100, 100))
            assertTrue(runCatching { decoder.decode() }.exceptionOrNull() is IOException)
        }
    }
}
