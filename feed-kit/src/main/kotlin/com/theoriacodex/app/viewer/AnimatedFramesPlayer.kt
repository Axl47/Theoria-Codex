package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The clock of a decoded frame sequence (GIF, ugoira) and whether its timeline is being dragged. */
@Stable
class AnimatedFrames internal constructor(val durationMs: Long) {
    internal val playback = AnimationPlaybackState(durationMs)

    /** Read only by effects and gestures, so a drag never recomposes the frame. */
    internal var scrubbing = false

    val positionMs: Long get() = playback.positionMs
}

/**
 * Plays a decoded frame sequence of [durationMs] on [controls]: reports its duration and readiness once, advances at
 * the chosen rate and loop mode while active and playing, rewinds on a restart, and applies seek jumps. A change in
 * [keys] (the loaded animation) starts a new clock at 0. [availableUntilMs] holds playback at the end of the frames
 * decoded so far. Inactive media pauses where it is, so a swipe away and back resumes it.
 */
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
    // The Viewer counts readiness only for its current media, so frames decoded while preloading report again once
    // this media becomes active.
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

/**
 * [frames] laid out in the Viewer: [content] draws the current frame, the timeline shows with the controls, and
 * passive progress runs along the edge of the media fitted to [aspectRatio] while they are hidden. [preview] draws the
 * frame at a scrub position.
 */
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
