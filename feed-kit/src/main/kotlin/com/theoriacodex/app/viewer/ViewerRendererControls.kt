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
) {
    companion object {
        /** A feed card's animation: plays and loops while [isActive], with no timeline and no route commands. */
        fun feedCard(isActive: Boolean) = ViewerRendererControls(
            isActive = isActive, isPlaying = true, showControls = false, loopPlayback = true,
            playbackRate = 1f, restartRequest = 0L, loadGeneration = 0L,
        )
    }
}

/**
 * Where an episode's playback is, at [positionMs] of an episode [durationMs] long, as the Viewer reports it for its
 * series' progress ([ViewerRendererEvents.onPlaybackProgress]).
 */
data class ViewerPlaybackProgress(val positionMs: Long, val durationMs: Long)

data class ViewerRendererEvents(
    val onPlaybackCompleted: () -> Unit,
    val onError: (String) -> Unit,
    val onReady: () -> Unit,
    val onDurationKnown: (Long) -> Unit,
    val onTogglePlayback: () -> Unit,
    val onRestartPlayback: () -> Unit,
    val onPlaybackIntentChanged: (Boolean) -> Unit,
    val onTimelineInteractionActiveChanged: (Boolean) -> Unit,
    /**
     * Hears where an episode's playback is: periodically while it plays, when it pauses, after a seek, and as its
     * player lets go; never while the duration is unknown. Null for every other video, which reports nothing.
     */
    val onPlaybackProgress: ((ViewerPlaybackProgress) -> Unit)? = null,
) {
    companion object {
        /** A feed card's events: it reports only the duration it learns. */
        fun feedCard(onDurationKnown: (Long) -> Unit) = ViewerRendererEvents(
            onPlaybackCompleted = {}, onError = {}, onReady = {}, onDurationKnown = onDurationKnown,
            onTogglePlayback = {}, onRestartPlayback = {}, onPlaybackIntentChanged = {}, onTimelineInteractionActiveChanged = {},
        )
    }
}

