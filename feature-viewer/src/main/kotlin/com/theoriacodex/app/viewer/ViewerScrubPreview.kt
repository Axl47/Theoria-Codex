@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * Scrub previews for the stream a Viewer page's [player] is playing, at the size the popup shows them. The page
 * keeps one while its timeline is composed; each drag's popup follows it through [ViewerVideoScrubPreview].
 */
@Composable
internal fun rememberViewerScrubPreviews(player: ExoPlayer?, playback: VideoPlaybackRequest): ViewerScrubPreviews? {
    val context = LocalContext.current
    // Extract at the size actually shown: fixed small frames were enlarged about threefold on high-density screens.
    val frameSize = viewerScrubFrameSize(LocalViewerMediaAspectRatio.current)
    val (widthPx, heightPx) = with(LocalDensity.current) { frameSize.width.roundToPx().even() to frameSize.height.roundToPx().even() }
    val previews = remember(player, playback, widthPx, heightPx) {
        player?.let { ViewerScrubPreviews(context, it, playback, widthPx, heightPx) }
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
 * Scrub previews of [player]'s stream, requested as its [playback] is (headers, container type, cache), read by a
 * [ScrubPreviewPlayer] at [widthPx] × [heightPx]. Frames need neither subtitles nor separate audio.
 */
fun ViewerScrubPreviews(context: Context, player: ExoPlayer, playback: VideoPlaybackRequest, widthPx: Int, heightPx: Int): ViewerScrubPreviews =
    ViewerScrubPreviews(player) { location ->
        val frames = playback.copy(location = location, subtitles = emptyList(), audioTracks = emptyList())
        ScrubPreviewPlayer(context, context.videoPlaybackInfrastructure().bind(frames), widthPx, heightPx)
    }

/**
 * A Viewer page's scrub previews. [follow] serves one drag: one pending frame, and only the newest target may
 * publish. Every drag reads from the page's one frame source ([openFrames] for the stream's location: a
 * [ScrubPreviewPlayer]), built on the first drag and kept, idle between drags, until [close], or until a drag fails,
 * so the next one starts afresh. When a drag is released, [seekReleased] seeks [player]. Use it on the main thread.
 */
class ViewerScrubPreviews(
    private val player: ExoPlayer,
    private val openFrames: (location: String) -> ScrubFrameSource,
) : AutoCloseable {
    private var preview: Pair<String, ScrubFrameSource>? = null

    /** While a drag is followed: the target whose frame was last published, and that frame's keyframe position. */
    private var shown: Pair<Long, Long>? = null

    /** Publishes a frame for the newest of [targets] until cancelled (see [followScrubTargets]). */
    suspend fun follow(targets: StateFlow<Long>, publish: (Long, Bitmap) -> Unit) {
        val location = checkNotNull(player.currentMediaItem?.localConfiguration?.uri) { "No stream to preview" }.toString()
        val frames = previewPlayer(location)
        var healthy = true
        try {
            followScrubTargets(targets, { positionMs -> withTimeout(SCRUB_FRAME_TIMEOUT_MS) { frames.frameAt(positionMs) } }) { requested, frame ->
                shown = requested to frame.positionMs
                publish(requested, frame.bitmap)
            }
        } catch (timeout: TimeoutCancellationException) {
            healthy = false
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            healthy = false
            throw error
        } finally {
            shown = null
            // A drag that ended keeps the player, idle; a failed or stuck one is replaced on the next drag.
            if (healthy) frames.idle() else close()
        }
    }

    /**
     * Seeks [player] for a drag released on [targetMs], while [follow] still serves it, and returns where playback
     * lands. When the last published frame is [targetMs]'s, playback lands on that frame's keyframe: the frame the user
     * saw, which the preview already downloaded, where an exact seek decodes from the keyframe before [targetMs] and
     * needs bytes the preview never fetched. Otherwise the seek is exact.
     */
    fun seekReleased(targetMs: Long): Long {
        val keyframeMs = shown?.takeIf { (shownTargetMs, _) -> shownTargetMs == targetMs }?.second
        // The drag is over. Its preview stops before the player seeks: until its request closes, what it read is
        // uncommitted and its cache lock makes the player download those bytes again.
        preview?.second?.idle()
        if (keyframeMs == null) return targetMs.also(player::seekTo)
        // The keyframe's time is truncated to milliseconds; an exact seek there would decode from the keyframe before.
        val exactness = player.seekParameters
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.seekTo(keyframeMs)
        player.setSeekParameters(exactness)
        return keyframeMs
    }

    override fun close() {
        preview?.second?.close()
        preview = null
    }

    private fun previewPlayer(location: String): ScrubFrameSource {
        preview?.let { (boundLocation, frames) -> if (boundLocation == location) return frames }
        close()
        return openFrames(location).also { preview = location to it }
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
