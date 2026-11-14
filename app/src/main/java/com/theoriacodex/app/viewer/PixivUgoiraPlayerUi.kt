package com.theoriacodex.app.viewer


import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.theoriacodex.app.search.FEED_PLAYER_ACTIVATION_DELAY_MS
import kotlinx.coroutines.delay

@Composable
fun PixivUgoiraPlayer(
    postId: String,
    client: PixivUgoiraClient,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    loopPlayback: Boolean = true,
    onPlaybackCompleted: () -> Unit = {},
    contentScale: ContentScale = ContentScale.Fit,
    sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    showProgressBar: Boolean = false,
    isActive: Boolean = true,
    isPlaying: Boolean? = null,
    seekJumpSerial: Int = 0,
    seekJumpDeltaMs: Long = 0L,
    playbackRate: Float = 1f,
    restartRequest: Long = 0L,
    loadGeneration: Long = 0L,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit = {},
    onTogglePlayback: (() -> Unit)? = null,
    onDurationKnown: (Long) -> Unit = {},
    onError: (String) -> Unit = {},
    loadingPreview: (@Composable () -> Unit)? = null,
    showPassiveProgress: Boolean = false,
    mediaModifier: Modifier = Modifier,
    isPreloading: Boolean = false,
    /** The animation's length as known before it loads, shown on a 0:00 timeline meanwhile. */
    expectedDurationMs: Long? = null,
) {
    var playback by remember(postId, client, sizeBucket, loadGeneration) {
        mutableStateOf(client.cached(postId, sizeBucket))
    }
    var errorMessage by remember(postId, loadGeneration) { mutableStateOf<String?>(null) }
    // Read only by effects and gestures, so scrubbing never recomposes the frame.
    val isScrubbing = remember(postId, loadGeneration) { mutableStateOf(false) }
    var playbackPaused by remember(postId, loadGeneration) { mutableStateOf(false) }

    val shouldLoad = isActive || isPreloading
    LaunchedEffect(postId, client, sizeBucket, shouldLoad, loadGeneration) {
        isScrubbing.value = false
        playbackPaused = false
        errorMessage = null
        playback = client.cached(postId, sizeBucket) ?: playback?.takeIf { it.isComplete }
        if (playback != null || !shouldLoad) return@LaunchedEffect
        if (sizeBucket == UgoiraSizeBucket.CARD) delay(FEED_PLAYER_ACTIVATION_DELAY_MS)
        client.observeLoad(postId, sizeBucket).collect { result ->
            result.onSuccess { playback = it }.onFailure { error ->
                playback = null
                errorMessage = error.message ?: "Could not load animation"
                onError(errorMessage.orEmpty())
            }
        }
    }

    val paused = isPlaying?.not() ?: playbackPaused
    val togglePlayback = { if (onTogglePlayback != null) onTogglePlayback() else playbackPaused = !playbackPaused }
    val activePlayback = playback
    if (activePlayback == null) {
        UgoiraLoadingState(errorMessage, modifier, loadingPreview) {
            val expected = expectedDurationMs?.takeIf { it > 0L && shouldLoad } ?: return@UgoiraLoadingState
            ViewerPendingTimeline(expected, showProgressBar, paused, playbackRate, togglePlayback, onTimelineInteractionActiveChanged)
        }
        return
    }
    UgoiraLoadedPlayer(
        playback = activePlayback,
        postId = postId,
        client = client,
        sizeBucket = sizeBucket,
        loadGeneration = loadGeneration,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        mediaModifier = mediaModifier,
        isActive = isActive,
        paused = paused,
        loop = loopPlayback,
        showProgressBar = showProgressBar,
        showPassiveProgress = showPassiveProgress,
        playbackRate = playbackRate,
        seekJumpSerial = seekJumpSerial,
        seekJumpDeltaMs = seekJumpDeltaMs,
        restartRequest = restartRequest,
        isScrubbing = isScrubbing,
        onPlaybackCompleted = onPlaybackCompleted,
        onDurationKnown = onDurationKnown,
        onTimelineInteractionActiveChanged = onTimelineInteractionActiveChanged,
        onTogglePlayback = togglePlayback,
    )
}

/** Plays frames that are already available; loading and its failures stay with [PixivUgoiraPlayer]. */
@Composable
private fun UgoiraLoadedPlayer(
    playback: UgoiraPlayback,
    postId: String,
    client: PixivUgoiraClient,
    sizeBucket: UgoiraSizeBucket,
    loadGeneration: Long,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    mediaModifier: Modifier,
    isActive: Boolean,
    paused: Boolean,
    loop: Boolean,
    showProgressBar: Boolean,
    showPassiveProgress: Boolean,
    playbackRate: Float,
    seekJumpSerial: Int,
    seekJumpDeltaMs: Long,
    restartRequest: Long,
    isScrubbing: MutableState<Boolean>,
    onPlaybackCompleted: () -> Unit,
    onDurationKnown: (Long) -> Unit,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit,
    onTogglePlayback: () -> Unit,
) {
    val frameEndsMs = remember(playback.frameDelaysMs) { ugoiraFrameEndsMs(playback.frameDelaysMs) }
    val totalDurationMs = frameEndsMs.last()
    val animation = remember(postId, client, sizeBucket, loadGeneration, frameEndsMs) {
        AnimationPlaybackState(totalDurationMs)
    }
    val frameIndex by remember(animation, frameEndsMs) {
        derivedStateOf { animationFrameIndexAt(animation.positionMs, frameEndsMs) }
    }
    LaunchedEffect(postId, totalDurationMs) { onDurationKnown(totalDurationMs) }
    val maxSeekablePositionMs = totalDurationMs - 1L
    fun seekToPosition(targetMs: Long) {
        animation.seekTo(targetMs.coerceIn(0L, maxSeekablePositionMs))
    }
    LaunchedEffect(restartRequest, animation, isActive) {
        if (restartRequest > 0L || !isActive) seekToPosition(0L)
    }
    LaunchedEffect(seekJumpSerial, seekJumpDeltaMs, maxSeekablePositionMs, isActive) {
        if (seekJumpSerial > 0 && seekJumpDeltaMs != 0L && !isScrubbing.value && isActive) {
            seekToPosition(animation.positionMs + seekJumpDeltaMs)
        }
    }
    AnimatePlayback(
        state = animation,
        enabled = isActive && !paused,
        rate = playbackRate.coerceAtLeast(0.1f),
        loop = loop,
        onCompleted = onPlaybackCompleted,
        availableUntilMs = frameEndsMs[playback.frames.lastIndex],
    )

    val frame = playback.frames[frameIndex.coerceAtMost(playback.frames.lastIndex)]
    if (!showProgressBar && !showPassiveProgress) {
        Image(frame.bitmap.asImageBitmap(), contentDescription, modifier.then(mediaModifier), contentScale = contentScale)
        return
    }
    Box(modifier = modifier) {
        Image(
            bitmap = frame.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().then(mediaModifier),
            contentScale = contentScale,
        )
        if (!showProgressBar) {
            ViewerMediaPassiveProgress({ animation.positionMs }, totalDurationMs, viewerAspectRatio(frame.bitmap.width, frame.bitmap.height))
        }
        ViewerChromeVisibility(showProgressBar, Modifier.align(Alignment.BottomCenter)) {
            ViewerTimelineVeil {
                ViewerPlaybackFooter {
                    UgoiraTimeline(
                        playback, frameEndsMs, animation, paused, playbackRate, isScrubbing, onSeek = { seekToPosition(it) },
                        onInteractionActiveChanged = onTimelineInteractionActiveChanged, onTogglePlayback = onTogglePlayback,
                    )
                }
            }
        }
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
private fun UgoiraTimeline(
    playback: UgoiraPlayback,
    frameEndsMs: LongArray,
    animation: AnimationPlaybackState,
    paused: Boolean,
    playbackRate: Float,
    isScrubbing: MutableState<Boolean>,
    onSeek: (Long) -> Unit,
    onInteractionActiveChanged: (Boolean) -> Unit,
    onTogglePlayback: () -> Unit,
) {
    val totalDurationMs = frameEndsMs.last()
    MediaTimelineBar(
        positionMs = { animation.positionMs },
        durationMs = totalDurationMs,
        onSeekStarted = { isScrubbing.value = true },
        onSeekCancelled = { isScrubbing.value = false },
        preview = { target ->
            val previewIndex = animationFrameIndexAt(target.coerceAtMost(totalDurationMs - 1L), frameEndsMs)
            val previewFrame = playback.frames.getOrNull(previewIndex)
            if (previewFrame != null) Image(previewFrame.bitmap.asImageBitmap(), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Preview buffering", color = androidx.compose.ui.graphics.Color.White)
        },
        onSeekFinished = { target ->
            onSeek(target)
            isScrubbing.value = false
        },
        onInteractionActiveChanged = onInteractionActiveChanged,
        playbackRate = playbackRate,
        leading = {
            TimelinePlaybackButton(
                isPaused = paused,
                onToggle = {
                    onTogglePlayback()
                    onInteractionActiveChanged(true)
                    onInteractionActiveChanged(false)
                },
            )
        },
    )
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
