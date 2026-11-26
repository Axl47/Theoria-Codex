@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import com.theoriacodex.domain.coroutines.runCatchingUnlessCallerCancelled
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

@Composable
internal fun rememberViewerScrubPreviews(player: ExoPlayer?, playback: VideoPlaybackRequest): ViewerScrubPreviews? {
    val context = LocalContext.current
    val frameSize = viewerScrubFrameSize(LocalViewerMediaAspectRatio.current)
    val (widthPx, heightPx) = with(LocalDensity.current) { frameSize.width.roundToPx().even() to frameSize.height.roundToPx().even() }
    val previews = remember(player, playback, widthPx, heightPx) {
        player?.let { ViewerScrubPreviews(context, it, playback, widthPx, heightPx) }
    }
    DisposableEffect(previews) { onDispose { previews?.close() } }
    return previews
}

@Composable
internal fun ViewerVideoScrubPreview(previews: ViewerScrubPreviews, positionMs: Long) {
    val targets = remember(previews) { MutableStateFlow(positionMs) }
    SideEffect { targets.value = positionMs }
    var frame by remember(previews) { mutableStateOf<Pair<Long, Bitmap>?>(null) }
    var failed by remember(previews) { mutableStateOf(false) }
    LaunchedEffect(previews) {
        runCatchingUnlessCallerCancelled {
            previews.follow(targets) { requested, bitmap -> frame = requested to bitmap }
        }.onFailure { failed = true }
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

private fun Int.even(): Int = this and 1.inv()

fun ViewerScrubPreviews(context: Context, player: ExoPlayer, playback: VideoPlaybackRequest, widthPx: Int, heightPx: Int): ViewerScrubPreviews =
    ViewerScrubPreviews(player) { location ->
        val frames = playback.copy(location = location, subtitles = emptyList(), audioTracks = emptyList())
        ScrubPreviewPlayer(context, context.videoPlaybackInfrastructure().bind(frames), widthPx, heightPx)
    }

class ViewerScrubPreviews(
    private val player: ExoPlayer,
    private val openFrames: (location: String) -> ScrubFrameSource,
) : AutoCloseable {
    private var preview: Pair<String, ScrubFrameSource>? = null

    private var shown: Pair<Long, Long>? = null

    suspend fun follow(targets: StateFlow<Long>, publish: (Long, Bitmap) -> Unit) {
        val location = checkNotNull(player.currentMediaItem?.localConfiguration?.uri) { "No stream to preview" }.toString()
        val frames = previewPlayer(location)
        var healthy = true
        try {
            runCatchingUnlessCallerCancelled {
                followScrubTargets(targets, { positionMs -> withTimeout(SCRUB_FRAME_TIMEOUT_MS) { frames.frameAt(positionMs) } }) { requested, frame ->
                    shown = requested to frame.positionMs
                    publish(requested, frame.bitmap)
                }
            }.onFailure { healthy = false }.getOrThrow()
        } finally {
            shown = null
            if (healthy) frames.idle() else close()
        }
    }

    fun seekReleased(targetMs: Long): Long {
        val shownMs = shown?.takeIf { (shownTargetMs, _) -> shownTargetMs == targetMs }?.second
        preview?.second?.idle()
        if (shownMs == null) return targetMs.also(player::seekTo)
        val exactness = player.seekParameters
        player.setSeekParameters(SHOWN_FRAME_SEEK)
        player.seekTo(shownMs)
        player.setSeekParameters(exactness)
        return shownMs
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

internal val SHOWN_FRAME_SEEK = SeekParameters(0L, 1_000L)

internal const val SCRUB_FRAME_SETTLE_MS = 80L

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
