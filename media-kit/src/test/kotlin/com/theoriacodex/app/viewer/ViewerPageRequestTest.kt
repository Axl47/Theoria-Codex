package com.theoriacodex.app.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.request.SuccessResult
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerPageRequestTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `a tall strip decodes at least as wide as the screen`() {
        val viewport = context.resources.displayMetrics.widthPixels
        val strip = folder.newFile("strip.png")
        Bitmap.createBitmap(viewport * 2, viewport * 20, Bitmap.Config.ARGB_8888).run {
            eraseColor(Color.RED)
            strip.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val loader = ImageLoader(context)
        try {
            // Hardware bitmaps need a GPU; the decode size is the same either way.
            val request = buildViewerPageRequest(context, strip.path, SourceKey.PIXIV).newBuilder().allowHardware(false).build()
            // Coil finishes on the main thread, which is this one, so its looper runs while the decode completes.
            val result = MainScope().async { loader.execute(request) }
            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            while (!result.isCompleted) {
                check(System.currentTimeMillis() < deadline) { "decode did not finish" }
                ShadowLooper.idleMainLooper()
                Thread.sleep(5)
            }
            val bitmap = ((runBlocking { result.await() } as SuccessResult).drawable as BitmapDrawable).bitmap
            assertTrue("decoded ${bitmap.width}px wide for a ${viewport}px screen", bitmap.width >= viewport)
            assertEquals(bitmap.width * 10, bitmap.height)
        } finally {
            loader.shutdown()
        }
    }

    @Test fun `the decode box stays within the GPU's texture edge and the bitmap budget`() {
        assertEquals(1080 to 8192, viewerPageDecodeBounds(viewportWidthPx = 1080, maxEdgePx = 8192))
        assertEquals(4096 to 4096, viewerPageDecodeBounds(viewportWidthPx = 5000, maxEdgePx = 4096))
        // 64 MiB of 4-byte pixels at 2560 wide.
        assertEquals(2560 to 6553, viewerPageDecodeBounds(viewportWidthPx = 2560, maxEdgePx = 16384))
    }

    private companion object {
        const val DECODE_TIMEOUT_MS = 30_000L
    }
}
