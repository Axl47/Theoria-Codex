package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.imageLoader
import coil.request.SuccessResult
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.toViewerMediaState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A generated tall strip, served over loopback, decoded on the device's GPU as the vertical reader and its prefetch
 * request it. Logs each decoded size under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class ViewerPageDecodeDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aTallStripInAnUnknownShapeSlotDecodesForTheScreenWidth() = stripServer { server ->
        val viewport = context.resources.displayMetrics.widthPixels
        var slotSized by mutableStateOf<Size?>(null)
        var pageSized by mutableStateOf<Size?>(null)
        compose.setContent {
            // The slot a page lays out in before its shape is known.
            Column {
                AsyncImage(
                    buildViewerImageRequest(context, "${server.baseUrl}/slot.jpg", SOURCE), null,
                    Modifier.fillMaxWidth().aspectRatio(0.7f), onSuccess = { slotSized = it.painter.intrinsicSize },
                )
                AsyncImage(
                    buildViewerPageRequest(context, "${server.baseUrl}/page.jpg", SOURCE, viewport), null,
                    Modifier.fillMaxWidth().aspectRatio(0.7f), onSuccess = { pageSized = it.painter.intrinsicSize },
                )
            }
        }
        compose.waitUntil(DECODE_TIMEOUT_MS) { slotSized != null && pageSized != null }
        Log.i(TAG, "viewport=${viewport}px strip=${STRIP_WIDTH}x$STRIP_HEIGHT slotSized=${slotSized.px()} pageSized=${pageSized.px()}")
        val page = requireNotNull(pageSized)
        // Full width, unless the strip is taller than this GPU draws at that width (4096px at the least).
        assertTrue("page decoded ${page.px()} for a ${viewport}px screen", page.width >= viewport || page.height >= MIN_TEXTURE_EDGE_PX)
        assertEquals(STRIP_HEIGHT.toFloat() / STRIP_WIDTH, page.height / page.width, 0.01f)
    }

    @Test
    fun aPrefetchedPageIsTheBitmapThePageShows() = stripServer { server ->
        val viewport = context.resources.displayMetrics.widthPixels
        val url = "${server.baseUrl}/prefetched.jpg"
        val ref = ImageRef(url = url, localPath = null, mime = "image/jpeg")
        val post = Post(
            PostId(SOURCE, "decode"), ref, ref, pageUrl = null, width = STRIP_WIDTH, height = STRIP_HEIGHT,
            canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = 1L, media = listOf(ref),
        )
        runBlocking {
            val prefetched = prefetchViewerMedia(
                context, ref.toViewerMediaState(post, 0), VideoQuality.AUTO, keepDecodedInMemory = true, pageWidthPx = viewport,
            )
            assertEquals(ViewerPrefetchOutcome.WARMED, prefetched.outcome)
            val shown = context.imageLoader.execute(buildViewerPageRequest(context, url, SOURCE, viewport)) as SuccessResult
            val bitmap = shown.drawable.let { it.intrinsicWidth to it.intrinsicHeight }
            Log.i(TAG, "prefetched page shows from ${shown.dataSource} at ${bitmap.first}x${bitmap.second}")
            assertEquals(DataSource.MEMORY_CACHE, shown.dataSource)
        }
    }

    private fun Size?.px() = this?.let { "${it.width.toInt()}x${it.height.toInt()}" }

    /** Serves one generated strip under any name, on a fresh memory cache so every name decodes anew. */
    private fun stripServer(block: (BenchMediaServer) -> Unit) {
        check(context.packageName.startsWith("com.theoriacodex.") && context.packageName != "com.theoriacodex")
        val root = File(context.cacheDir, "page-decode-fixture").apply { deleteRecursively(); mkdirs() }
        val strip = Bitmap.createBitmap(STRIP_WIDTH, STRIP_HEIGHT, Bitmap.Config.RGB_565)
        Canvas(strip).apply {
            val paint = Paint()
            for (band in 0 until STRIP_HEIGHT / BAND_PX) {
                paint.color = if (band % 2 == 0) Color.rgb(40, 90, 200) else Color.rgb(230, 200, 60)
                drawRect(0f, band * BAND_PX.toFloat(), STRIP_WIDTH.toFloat(), (band + 1) * BAND_PX.toFloat(), paint)
            }
        }
        val names = listOf("slot.jpg", "page.jpg", "prefetched.jpg")
        File(root, names.first()).outputStream().use { strip.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        strip.recycle()
        names.drop(1).forEach { File(root, names.first()).copyTo(File(root, it)) }
        context.imageLoader.memoryCache?.clear()
        BenchMediaServer(root, bytesPerSecond = UNTHROTTLED, ttfbMs = 0).use(block)
    }

    private companion object {
        const val TAG = "ViewerPageDecode"
        /** A webtoon strip wider than a phone screen, short enough that its screen-width decode fits an 8192px texture. */
        const val STRIP_WIDTH = 1600
        const val STRIP_HEIGHT = 9_600
        const val BAND_PX = 400
        const val UNTHROTTLED = 1L shl 40
        const val MIN_TEXTURE_EDGE_PX = 4096
        const val DECODE_TIMEOUT_MS = 20_000L
        val SOURCE = SourceKey.RULE34VIDEO
    }
}
