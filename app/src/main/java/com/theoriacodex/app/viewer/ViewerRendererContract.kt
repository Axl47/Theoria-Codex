package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity

/** Route commands only. Renderer frame positions and decoder state never enter this contract. */
internal data class ViewerRendererControls(
    val isActive: Boolean,
    val isPlaying: Boolean,
    val showControls: Boolean,
    val loopPlayback: Boolean,
    val playbackRate: Float,
    val restartRequest: Long,
    val loadGeneration: Long,
    val seekJumpSerial: Int = 0,
    val seekJumpDeltaMs: Long = 0L,
)

internal data class ViewerRendererEvents(
    val onPlaybackCompleted: () -> Unit,
    val onError: (String) -> Unit,
    val onReady: () -> Unit,
    val onDurationKnown: (Long) -> Unit,
    val onTogglePlayback: () -> Unit,
    val onRestartPlayback: () -> Unit,
    val onPlaybackIntentChanged: (Boolean) -> Unit,
    val onTimelineInteractionActiveChanged: (Boolean) -> Unit,
)

/** Bind asynchronous renderer events to the producing session, media, and retry generation. */
internal fun viewerRendererEvents(
    session: ViewerSessionIdentity?,
    key: ViewerMediaKey,
    generation: Long,
    onAction: (ViewerAction) -> Unit,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit,
    onDurationKnown: (Long) -> Unit,
): ViewerRendererEvents = ViewerRendererEvents(
    onPlaybackCompleted = { session?.let { onAction(ViewerAction.PlaybackCompleted(it, key, generation)) } },
    onError = { message -> session?.let {
        onAction(ViewerAction.MediaFailed(it, ViewerMediaError.Recoverable(key, message), generation))
    } },
    onReady = { session?.let { onAction(ViewerAction.MediaReady(it, key, generation)) } },
    onDurationKnown = onDurationKnown,
    onTogglePlayback = { onAction(ViewerAction.TogglePlayback) },
    onRestartPlayback = { onAction(ViewerAction.RestartPlayback) },
    onPlaybackIntentChanged = { onAction(if (it) ViewerAction.Play else ViewerAction.Pause) },
    onTimelineInteractionActiveChanged = onTimelineInteractionActiveChanged,
)

internal const val MIN_VIEWER_PLAYBACK_RATE = 0.1f
