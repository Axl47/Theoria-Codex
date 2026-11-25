package com.theoriacodex.app.viewer.state

import kotlinx.coroutines.delay

internal const val VIEWER_SLIDESHOW_IMAGE_MS = 5_000L

data class ViewerDwell(val startedAtMs: Long? = null, val held: Boolean = false) {
    fun elapsed(atMs: Long): Boolean = startedAtMs == null || atMs - startedAtMs >= VIEWER_SLIDESHOW_IMAGE_MS
}

internal val ViewerUiState.autoAdvanceHeld: Boolean
    get() = controls.dwell.held && (controls.playbackMode == ViewerPlaybackMode.NEXT || controls.slideshow)

internal fun ViewerUiState.slideshowImageHold(): ViewerMediaKey? {
    val media = currentMedia ?: return null
    if (!controls.slideshow || controls.playback.available || controls.dwell.held) return null
    return media.key.takeIf { media.ready || mediaError != null }
}

internal suspend fun holdViewerSlideshowImage(state: ViewerUiState, onAction: (ViewerAction) -> Unit) {
    val key = state.slideshowImageHold() ?: return
    val session = state.session ?: return
    delay(VIEWER_SLIDESHOW_IMAGE_MS)
    onAction(ViewerAction.SlideshowAdvance(session, key))
}
