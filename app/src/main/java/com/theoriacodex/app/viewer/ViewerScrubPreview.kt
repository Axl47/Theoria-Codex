@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.abs
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.media3.common.Player
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The current media's width over height, so scrub previews can take its real shape. */
internal val LocalViewerMediaAspectRatio = staticCompositionLocalOf<Float?> { null }

private val SCRUB_FRAME_LONG_EDGE = 224.dp
private val SCRUB_FRAME_PORTRAIT_HEIGHT = 168.dp
private val SCRUB_FRAME_MIN_EDGE = 72.dp

/** A preview frame in the media's shape, with both edges clamped so extreme panoramas stay visible. */
internal fun viewerScrubFrameSize(aspectRatio: Float?): DpSize {
    val ratio = aspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
    return if (ratio >= 1f) {
        DpSize(SCRUB_FRAME_LONG_EDGE, (SCRUB_FRAME_LONG_EDGE / ratio).coerceAtLeast(SCRUB_FRAME_MIN_EDGE))
    } else {
        DpSize((SCRUB_FRAME_PORTRAIT_HEIGHT * ratio).coerceAtLeast(SCRUB_FRAME_MIN_EDGE), SCRUB_FRAME_PORTRAIT_HEIGHT)
    }
}

internal fun formatScrubDelta(deltaMs: Long): String =
    "${if (deltaMs < 0L) "-" else "+"}${formatTimelineTime(abs(deltaMs))}"

/** A floating thumbnail is presentation only; the track below owns the drag gesture. */
@Composable
internal fun ViewerScrubPreview(positionMs: Long, deltaMs: Long, progress: Float, content: @Composable () -> Unit) {
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(progress, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.left + anchorBounds.width * progress - popupContentSize.width / 2f).roundToInt()
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0),
            )
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(
        focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
    )) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.testTag("viewer-scrub-preview"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(viewerScrubFrameSize(LocalViewerMediaAspectRatio.current)).shadow(12.dp, shape).background(Color.Black, shape).clip(shape),
                contentAlignment = Alignment.Center,
            ) { content() }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatTimelineTime(positionMs), color = Color.White, style = ViewerVeil.LargeReadout, fontWeight = FontWeight.Medium)
                Text(formatScrubDelta(deltaMs), Modifier.padding(bottom = 3.dp), color = Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA), style = ViewerVeil.Time)
            }
        }
    }
}

/**
 * Scrub previews for the stream a Viewer page's [player] is playing, at the size the popup shows them. The page
 * keeps one while its timeline is composed; each drag's popup follows it through [ViewerVideoScrubPreview].
 */
@Composable
internal fun rememberViewerScrubPreviews(player: Player?, source: SourceKey): ViewerScrubPreviews? {
    val context = LocalContext.current
    // Extract at the size actually shown: fixed small frames were enlarged about threefold on high-density screens.
    val frameSize = viewerScrubFrameSize(LocalViewerMediaAspectRatio.current)
    val (widthPx, heightPx) = with(LocalDensity.current) { frameSize.width.roundToPx().even() to frameSize.height.roundToPx().even() }
    val previews = remember(player, source, widthPx, heightPx) {
        player?.let { ViewerScrubPreviews(context, it, source, widthPx, heightPx) }
    }
    DisposableEffect(previews) { onDispose { previews?.close() } }
    return previews
}

/** One drag's popup content: the newest target's frame, a spinner while it is pending. */
@Composable
internal fun ViewerVideoScrubPreview(previews: ViewerScrubPreviews, positionMs: Long) {
    val targets = remember(previews) { MutableStateFlow(positionMs) }
    SideEffect { targets.value = positionMs }
    var frame by remember(previews) { mutableStateOf<Pair<Long, Bitmap>?>(null) }
    var failed by remember(previews) { mutableStateOf(false) }
    LaunchedEffect(previews) {
        try {
            previews.follow(targets) { requested, bitmap -> frame = requested to bitmap }
        } catch (_: TimeoutCancellationException) {
            failed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        frame?.second?.let { bitmap ->
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        when {
            failed -> Text("Preview unavailable", color = Color.White)
            frame?.first != positionMs -> CircularProgressIndicator(
                Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp,
            )
        }
    }
}

/** Video encoders and effects expect even dimensions. */
private fun Int.even(): Int = this and 1.inv()

/**
 * A Viewer page's scrub previews. [follow] serves one drag: one extractor, one pending frame, and only the newest
 * target may publish. Build, follow and close on the main thread.
 */
internal class ViewerScrubPreviews(
    private val context: Context,
    private val player: Player,
    private val source: SourceKey,
    private val widthPx: Int,
    private val heightPx: Int,
) : AutoCloseable {
    /** Publishes a frame for the newest of [targets] until cancelled. */
    suspend fun follow(targets: StateFlow<Long>, publish: (Long, Bitmap) -> Unit) {
        val location = checkNotNull(player.currentMediaItem?.localConfiguration?.uri) { "No stream to preview" }.toString()
        ScrubFrameSource(context, location, source, widthPx, heightPx).use { frames ->
            targets.collect { requested ->
                delay(SCRUB_FRAME_DEBOUNCE_MS)
                if (requested != targets.value) return@collect
                val bitmap = frames.frameAt(requested)
                if (requested == targets.value) publish(requested, bitmap)
            }
        }
    }

    override fun close() = Unit
}

private const val SCRUB_FRAME_DEBOUNCE_MS = 80L

/**
 * Scrub preview frames for one Viewer stream, read through the same shared video cache as playback.
 * Frames come from the nearest sync sample. Build and close it on the main thread.
 */
internal class ScrubFrameSource(
    context: Context,
    location: String,
    source: SourceKey,
    widthPx: Int,
    heightPx: Int,
) : AutoCloseable {
    private val extractor: FrameExtractor = context.videoPlaybackInfrastructure()
        .bind(location, source.requestHeaders())
        .let { request ->
            FrameExtractor.Builder(context, request.mediaItem)
                .setMediaSourceFactory(request.mediaSourceFactory)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setEffects(listOf(Presentation.createForWidthAndHeight(widthPx, heightPx, Presentation.LAYOUT_SCALE_TO_FIT)))
                .build()
        }

    suspend fun frameAt(positionMs: Long): Bitmap =
        withTimeout(SCRUB_FRAME_TIMEOUT_MS) { extractor.getFrame(positionMs).awaitPreviewFrame().bitmap }

    override fun close() = extractor.close()
}

private const val SCRUB_FRAME_TIMEOUT_MS = 6_000L

private suspend fun <T> ListenableFuture<T>.awaitPreviewFrame(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        try {
            continuation.resume(get())
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }, Executor { it.run() })
}
