package com.theoriacodex.app.viewer

/** Route commands only. Renderer frame positions and decoder state never enter this contract. */
data class ViewerRendererControls(
    val isActive: Boolean,
    val isPlaying: Boolean,
    /** Whether the timeline shows: on the selected media, and on the media a swipe is heading to. */
    val showControls: Boolean,
    val loopPlayback: Boolean,
    val playbackRate: Float,
    val restartRequest: Long,
    val loadGeneration: Long,
    val showPassiveProgress: Boolean = false,
    val isPreloading: Boolean = false,
    val seekJumpSerial: Int = 0,
    val seekJumpDeltaMs: Long = 0L,
    /** False while the Viewer opens: system media controls wait for it to land, since connecting them costs frames. */
    val systemControlsReady: Boolean = true,
    /** The media's length as known before its player loads; timelines show it at 0:00 meanwhile instead of vanishing. */
    val expectedDurationMs: Long? = null,
)

data class ViewerRendererEvents(
    val onPlaybackCompleted: () -> Unit,
    val onError: (String) -> Unit,
    val onReady: () -> Unit,
    val onDurationKnown: (Long) -> Unit,
    val onTogglePlayback: () -> Unit,
    val onRestartPlayback: () -> Unit,
    val onPlaybackIntentChanged: (Boolean) -> Unit,
    val onTimelineInteractionActiveChanged: (Boolean) -> Unit,
)
