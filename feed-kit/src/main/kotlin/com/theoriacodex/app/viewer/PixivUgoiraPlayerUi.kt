package com.theoriacodex.app.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.theoriacodex.app.search.FEED_PLAYER_ACTIVATION_DELAY_MS
import kotlinx.coroutines.delay

/**
 * Plays a Pixiv ugoira on [controls]: the Viewer's commands, or [ViewerRendererControls.feedCard] on a card. Frames
 * play as soon as the first ones decode; [loadingPreview] shows until then.
 */
@Composable
fun PixivUgoiraPlayer(
    postId: String,
    client: PixivUgoiraClient,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    loadingPreview: (@Composable () -> Unit)? = null,
) {
    val load = remember(postId, client, sizeBucket, controls.loadGeneration) { UgoiraLoad(client.cached(postId, sizeBucket)) }
    val shouldLoad = controls.isActive || controls.isPreloading
    LaunchedEffect(load, shouldLoad) {
        load.errorMessage = null
        load.playback = client.cached(postId, sizeBucket) ?: load.playback?.takeIf { it.isComplete }
        if (load.playback != null || !shouldLoad) return@LaunchedEffect
        if (sizeBucket == UgoiraSizeBucket.CARD) delay(FEED_PLAYER_ACTIVATION_DELAY_MS)
        client.observeLoad(postId, sizeBucket).collect { result ->
            result.onSuccess { load.playback = it }.onFailure { error ->
                load.playback = null
                val message = error.message ?: "Could not load animation"
                load.errorMessage = message
                events.onError(message)
            }
        }
    }
    val playback = load.playback
    if (playback == null) {
        UgoiraLoadingState(load.errorMessage, modifier, loadingPreview) { ViewerPendingTimeline(controls, events, Modifier) }
        return
    }
    UgoiraLoadedPlayer(playback, load, controls, events, contentDescription, contentScale, modifier, mediaModifier)
}

/** One load of a post's frames; a new post, client, size or retry generation starts another. */
private class UgoiraLoad(initial: UgoiraPlayback?) {
    var playback by mutableStateOf(initial)
    var errorMessage by mutableStateOf<String?>(null)
}

/** Plays frames that are already available; loading and its failures stay with [PixivUgoiraPlayer]. */
@Composable
private fun UgoiraLoadedPlayer(
    playback: UgoiraPlayback,
    load: UgoiraLoad,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    mediaModifier: Modifier,
) {
    val frameEndsMs = remember(playback.frameDelaysMs) { ugoiraFrameEndsMs(playback.frameDelaysMs) }
    val frames = rememberAnimatedFrames(
        load, frameEndsMs, durationMs = frameEndsMs.last(), controls = controls, events = events,
        availableUntilMs = frameEndsMs[playback.frames.lastIndex],
    )
    val frameIndex by remember(frames, frameEndsMs) {
        derivedStateOf { animationFrameIndexAt(frames.positionMs, frameEndsMs) }
    }
    val frame = playback.frames[frameIndex.coerceAtMost(playback.frames.lastIndex)]
    // Cards and off-screen pages draw the bare frame: no box, timeline or progress to lay out per frame.
    if (!controls.showControls && !controls.showPassiveProgress) {
        Image(frame.bitmap.asImageBitmap(), contentDescription, modifier.then(mediaModifier), contentScale = contentScale)
        return
    }
    AnimatedFramesPlayer(
        frames, controls, events, viewerAspectRatio(frame.bitmap.width, frame.bitmap.height), modifier,
        preview = { target -> UgoiraPreviewFrame(playback, frameEndsMs, target) },
    ) {
        Image(frame.bitmap.asImageBitmap(), contentDescription, Modifier.fillMaxSize().then(mediaModifier), contentScale = contentScale)
    }
}

private fun ugoiraFrameEndsMs(frameDelaysMs: List<Int>): LongArray {
    var endMs = 0L
    return LongArray(frameDelaysMs.size) { index ->
        endMs += frameDelaysMs[index].coerceAtLeast(16)
        endMs
    }
}

@Composable
private fun UgoiraPreviewFrame(playback: UgoiraPlayback, frameEndsMs: LongArray, targetMs: Long) {
    val index = animationFrameIndexAt(targetMs.coerceAtMost(frameEndsMs.last() - 1L), frameEndsMs)
    val frame = playback.frames.getOrNull(index)
    if (frame != null) Image(frame.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else Text("Preview buffering", color = Color.White)
}

@Composable
private fun UgoiraLoadingState(
    errorMessage: String?,
    modifier: Modifier,
    loadingPreview: (@Composable () -> Unit)?,
    pendingTimeline: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        loadingPreview?.invoke()
        if (errorMessage == null) {
            CircularProgressIndicator()
            Box(Modifier.align(Alignment.BottomCenter)) { pendingTimeline() }
        } else {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
