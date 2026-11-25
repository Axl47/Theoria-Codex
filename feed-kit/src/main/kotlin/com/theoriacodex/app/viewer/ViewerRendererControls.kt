package com.theoriacodex.app.viewer

data class ViewerRendererControls(
    val isActive: Boolean,
    val isPlaying: Boolean,
    val showControls: Boolean,
    val loopPlayback: Boolean,
    val playbackRate: Float,
    val restartRequest: Long,
    val loadGeneration: Long,
    val showPassiveProgress: Boolean = false,
    val isPreloading: Boolean = false,
    val seekJumpSerial: Int = 0,
    val seekJumpDeltaMs: Long = 0L,
    val systemControlsReady: Boolean = true,
    val expectedDurationMs: Long? = null,
    val showLeadingPlaybackButton: Boolean = true,
    val showSpeedChip: Boolean = true,
    val dimBackdrop: Boolean = false,
    val fillScreen: Boolean = false,
    val repeatsAtEnd: Boolean = loopPlayback,
) {
    companion object {
        fun feedCard(isActive: Boolean) = ViewerRendererControls(
            isActive = isActive, isPlaying = true, showControls = false, loopPlayback = true,
            playbackRate = 1f, restartRequest = 0L, loadGeneration = 0L,
        )
    }
}

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
    val onPlaybackProgress: ((ViewerPlaybackProgress) -> Unit)? = null,
) {
    companion object {
        fun feedCard(onDurationKnown: (Long) -> Unit) = ViewerRendererEvents(
            onPlaybackCompleted = {}, onError = {}, onReady = {}, onDurationKnown = onDurationKnown,
            onTogglePlayback = {}, onRestartPlayback = {}, onPlaybackIntentChanged = {}, onTimelineInteractionActiveChanged = {},
        )
    }
}

