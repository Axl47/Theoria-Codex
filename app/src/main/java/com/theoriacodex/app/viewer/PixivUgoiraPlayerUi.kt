package com.theoriacodex.app.viewer

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun PixivUgoiraPlayer(
    postId: String,
    client: PixivUgoiraClient,
    modifier: Modifier = Modifier,
    contentDescription: String?,
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
) {
    var playback by remember(postId, client, sizeBucket, loadGeneration) {
        mutableStateOf(client.cached(postId, sizeBucket))
    }
    var errorMessage by remember(postId, loadGeneration) { mutableStateOf<String?>(null) }
    var isScrubbing by remember(postId, loadGeneration) { mutableStateOf(false) }
    var playbackPaused by remember(postId, loadGeneration) { mutableStateOf(false) }
    val effectivePlaybackRate = playbackRate.coerceAtLeast(0.1f)
    val effectivePlaybackPaused = isPlaying?.not() ?: playbackPaused

    LaunchedEffect(postId, client, sizeBucket, isActive, loadGeneration) {
        isScrubbing = false
        playbackPaused = false
        errorMessage = null
        playback = client.cached(postId, sizeBucket)
        if (playback != null || !isActive) return@LaunchedEffect
        client.load(postId, sizeBucket).onSuccess { loaded ->
            playback = loaded
        }.onFailure { error ->
            errorMessage = error.message ?: "Could not load animation"
            onError(errorMessage.orEmpty())
        }
    }

    val activePlayback = playback
    if (activePlayback == null) {
        UgoiraLoadingState(errorMessage, modifier)
        return
    }

    val frameEndsMs = remember(activePlayback) {
        var endMs = 0L
        LongArray(activePlayback.frames.size) { index ->
            endMs += activePlayback.frames[index].delayMs.coerceAtLeast(16)
            endMs
        }
    }
    val totalDurationMs = frameEndsMs.last()
    val animation = remember(postId, loadGeneration, activePlayback) {
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
    LaunchedEffect(seekJumpSerial, seekJumpDeltaMs, maxSeekablePositionMs, isScrubbing, isActive) {
        if (seekJumpSerial > 0 && seekJumpDeltaMs != 0L && !isScrubbing && isActive) {
            seekToPosition(animation.positionMs + seekJumpDeltaMs)
        }
    }
    AnimatePlayback(
        state = animation,
        enabled = isActive && !isScrubbing && !effectivePlaybackPaused,
        rate = effectivePlaybackRate,
    )

    val frame = activePlayback.frames[frameIndex]
    if (!showProgressBar) {
        Image(frame.bitmap.asImageBitmap(), contentDescription, modifier, contentScale = contentScale)
        return
    }
    Box(modifier = modifier) {
        Image(
            bitmap = frame.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelinePlaybackButton(
                isPaused = effectivePlaybackPaused,
                onToggle = {
                    if (onTogglePlayback != null) onTogglePlayback() else playbackPaused = !playbackPaused
                    onTimelineInteractionActiveChanged(true)
                    onTimelineInteractionActiveChanged(false)
                },
            )
            MediaTimelineBar(
                positionMs = animation.positionMs,
                durationMs = totalDurationMs,
                onSeekStarted = { isScrubbing = true },
                onSeekChanged = ::seekToPosition,
                onSeekFinished = { target ->
                    seekToPosition(target)
                    isScrubbing = false
                },
                onInteractionActiveChanged = onTimelineInteractionActiveChanged,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UgoiraLoadingState(errorMessage: String?, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (errorMessage == null) {
            CircularProgressIndicator()
        } else {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
