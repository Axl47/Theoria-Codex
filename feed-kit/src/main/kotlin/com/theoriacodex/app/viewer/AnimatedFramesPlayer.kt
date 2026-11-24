package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Stable
class AnimatedFrames internal constructor(val durationMs: Long) {
    internal val playback = AnimationPlaybackState(durationMs)

    internal var scrubbing = false

    val positionMs: Long get() = playback.positionMs
}

@Composable
fun rememberAnimatedFrames(
    vararg keys: Any?,
    durationMs: Long,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    availableUntilMs: Long = Long.MAX_VALUE,
): AnimatedFrames {
    val frames = remember(*keys, durationMs) { AnimatedFrames(durationMs) }
    LaunchedEffect(frames) { events.onDurationKnown(durationMs) }
    LaunchedEffect(frames, controls.isActive) { events.onReady() }
    AnimatePlayback(
        frames.playback, controls.isActive && controls.isPlaying, controls.playbackRate,
        controls.loopPlayback, events.onPlaybackCompleted, availableUntilMs,
    )
    LaunchedEffect(frames, controls.restartRequest) { if (controls.restartRequest > 0L) frames.playback.seekTo(0L) }
    ViewerSeekJumps(controls.seekJumpSerial, controls.seekJumpDeltaMs, { controls.isActive && !frames.scrubbing }) {
        frames.playback.seekTo(frames.positionMs + it)
    }
    return frames
}

@Composable
fun AnimatedFramesPlayer(
    frames: AnimatedFrames,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    aspectRatio: Float?,
    modifier: Modifier,
    preview: @Composable (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        content()
        ViewerChromeVisibility(controls.showControls, Modifier.align(Alignment.BottomCenter)) {
            ViewerPlaybackTimeline(
                controls, events, { frames.positionMs }, frames.durationMs,
                seek = ViewerTimelineSeek(
                    onStarted = { frames.scrubbing = true },
                    onMoved = frames.playback::seekTo,
                    onFinished = { frames.playback.seekTo(it); frames.scrubbing = false },
                    onCancelled = { frames.scrubbing = false },
                    preview = preview,
                ),
            )
        }
        if (controls.showPassiveProgress) ViewerMediaPassiveProgress({ frames.positionMs }, frames.durationMs, aspectRatio)
    }
}
