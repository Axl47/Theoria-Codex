package com.theoriacodex.app.viewer.state

import kotlinx.coroutines.delay

/** How long a slideshow shows each still image. */
internal const val VIEWER_SLIDESHOW_IMAGE_MS = 5_000L

/**
 * The still image a running slideshow is timing, once it has loaded or failed. Null while no slideshow runs and
 * on videos and animated posts, which move on when they end instead (see [ViewerAction.PlaybackCompleted]).
 */
internal fun ViewerUiState.slideshowImageHold(): ViewerMediaKey? {
    val media = currentMedia ?: return null
    if (!controls.slideshow || controls.playback.available) return null
    return media.key.takeIf { media.ready || mediaError != null }
}

/**
 * Shows the still image [state] holds for [VIEWER_SLIDESHOW_IMAGE_MS], then asks to move on. Callers restart it whenever
 * [slideshowImageHold] changes; the reducer ignores an advance for media that is no longer current.
 */
internal suspend fun holdViewerSlideshowImage(state: ViewerUiState, onAction: (ViewerAction) -> Unit) {
    val key = state.slideshowImageHold() ?: return
    val session = state.session ?: return
    delay(VIEWER_SLIDESHOW_IMAGE_MS)
    onAction(ViewerAction.SlideshowAdvance(session, key))
}
