package com.theoriacodex.app.viewer.state

import com.theoriacodex.app.viewer.ViewerSeries
import kotlinx.coroutines.delay

internal const val VIEWER_SLIDESHOW_IMAGE_MS = 5_000L

data class ViewerDwell(val startedAtMs: Long? = null, val held: Boolean = false, val interacting: Boolean = false) {
    fun elapsed(atMs: Long): Boolean = remainingMs(atMs) == 0L

    fun remainingMs(atMs: Long): Long? = when {
        interacting -> null
        startedAtMs == null -> 0L
        else -> (startedAtMs + VIEWER_SLIDESHOW_IMAGE_MS - atMs).coerceAtLeast(0L)
    }
}

internal val ViewerUiState.dwellApplies: Boolean
    get() = (controls.playbackMode == ViewerPlaybackMode.NEXT || controls.slideshow) && series !is ViewerSeries.Watching

internal fun ViewerUiState.restartsAtEnd(dwellElapsed: Boolean): Boolean =
    dwellApplies && (controls.dwell.held || !dwellElapsed)

internal fun ViewerUiState.repeatsVideo(dwellElapsed: Boolean): Boolean = loopsPlayback || restartsAtEnd(dwellElapsed)

internal val ViewerUiState.autoAdvanceHeld: Boolean
    get() = controls.dwell.held && dwellApplies

internal fun ViewerUiState.slideshowImageHold(): ViewerMediaKey? {
    val media = currentMedia ?: return null
    if (!controls.slideshow || controls.playback.available || controls.dwell.held || controls.dwell.interacting) return null
    return media.key.takeIf { media.ready || mediaError != null }
}

internal suspend fun holdViewerSlideshowImage(state: ViewerUiState, dwellStartedAtMs: Long?, onAction: (ViewerAction) -> Unit) {
    val key = state.slideshowImageHold() ?: return
    val session = state.session ?: return
    delay(VIEWER_SLIDESHOW_IMAGE_MS)
    onAction(ViewerAction.SlideshowAdvance(session, key, dwellStartedAtMs))
}
