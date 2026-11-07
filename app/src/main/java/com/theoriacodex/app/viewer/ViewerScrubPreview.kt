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
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

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
 * A Viewer page's scrub previews. [follow] serves one drag: one pending frame, and only the newest target may
 * publish. Every drag reads from the page's one [ScrubPreviewPlayer], built on the first drag and kept, idle
 * between drags, until [close], or until a drag fails, so the next one starts afresh. Build, follow and close on
 * the main thread.
 */
internal class ViewerScrubPreviews(
    private val context: Context,
    private val player: Player,
    private val source: SourceKey,
    private val widthPx: Int,
    private val heightPx: Int,
) : AutoCloseable {
    private var preview: Pair<String, ScrubPreviewPlayer>? = null

    /** Publishes a frame for the newest of [targets] until cancelled (see [followScrubTargets]). */
    suspend fun follow(targets: StateFlow<Long>, publish: (Long, Bitmap) -> Unit) {
        val location = checkNotNull(player.currentMediaItem?.localConfiguration?.uri) { "No stream to preview" }.toString()
        val frames = previewPlayer(location)
        var healthy = true
        try {
            followScrubTargets(targets, { positionMs -> withTimeout(SCRUB_FRAME_TIMEOUT_MS) { frames.frameAt(positionMs) } }, publish)
        } catch (timeout: TimeoutCancellationException) {
            healthy = false
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            healthy = false
            throw error
        } finally {
            // A drag that ended keeps the player, idle; a failed or stuck one is replaced on the next drag.
            if (healthy) frames.idle() else close()
        }
    }

    override fun close() {
        preview?.second?.close()
        preview = null
    }

    private fun previewPlayer(location: String): ScrubPreviewPlayer {
        preview?.let { (boundLocation, frames) -> if (boundLocation == location) return frames }
        close()
        val request = context.videoPlaybackInfrastructure().bind(location, source.requestHeaders())
        return ScrubPreviewPlayer(context, request, widthPx, heightPx).also { preview = location to it }
    }
}

/** A later drag target is extracted only once the finger has rested on it this long. */
internal const val SCRUB_FRAME_SETTLE_MS = 80L

/**
 * One drag's frame loop: one pending frame, and only the newest target may publish. The first target, where the
 * finger landed, is extracted at once; later ones only once the finger rests on them for [SCRUB_FRAME_SETTLE_MS].
 */
internal suspend fun <F> followScrubTargets(targets: StateFlow<Long>, frameAt: suspend (Long) -> F, publish: (Long, F) -> Unit) {
    var landed = false
    targets.collect { requested ->
        if (landed) {
            delay(SCRUB_FRAME_SETTLE_MS)
            if (requested != targets.value) return@collect
        }
        landed = true
        val frame = frameAt(requested)
        if (requested == targets.value) publish(requested, frame)
    }
}

private const val SCRUB_FRAME_TIMEOUT_MS = 6_000L
