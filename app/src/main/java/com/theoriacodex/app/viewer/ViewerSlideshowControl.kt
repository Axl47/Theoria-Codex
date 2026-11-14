package com.theoriacodex.app.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.theoriacodex.app.viewer.state.VIEWER_SLIDESHOW_IMAGE_MS
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.holdViewerSlideshowImage
import com.theoriacodex.app.viewer.state.slideshowImageHold
import kotlinx.coroutines.launch

/**
 * The slideshow's button, lower right opposite the rotate button, offered on still images and kept while a slideshow
 * runs. While a still image is held it times the hold and draws the time left along the bottom edge; videos and
 * animated posts move on when they end. [suppressed] hides the button while another surface owns the bottom.
 */
@Composable
internal fun BoxScope.ViewerSlideshowControl(frame: ViewerFrame, suppressed: Boolean, onAction: (ViewerAction) -> Unit) {
    val state = frame.uiState
    val running = state.controls.slideshow
    val hold = state.slideshowImageHold()
    val currentState by rememberUpdatedState(state)
    val currentOnAction by rememberUpdatedState(onAction)
    // Drawn only; the hold itself is timed by delay, so disabled system animations cannot shorten it.
    val elapsed = remember { Animatable(0f) }
    LaunchedEffect(hold) {
        elapsed.snapTo(0f)
        if (hold == null) return@LaunchedEffect
        launch { elapsed.animateTo(1f, tween(VIEWER_SLIDESHOW_IMAGE_MS.toInt(), easing = LinearEasing)) }
        holdViewerSlideshowImage(currentState) { currentOnAction(it) }
    }
    if (hold != null) {
        ViewerPassiveProgressBar({ (elapsed.value * VIEWER_SLIDESHOW_IMAGE_MS).toLong() }, VIEWER_SLIDESHOW_IMAGE_MS, Modifier.align(Alignment.BottomCenter))
    }
    val offered = running || !state.controls.playback.available
    ViewerFooterButton(
        frame.selection, frame.chromeVisible, visible = frame.chromeVisible && offered && !suppressed, alignment = Alignment.BottomEnd,
        icon = if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = if (running) "Stop slideshow" else "Start slideshow", testTag = "viewer-slideshow",
        onClick = { onAction(ViewerAction.SetSlideshow(!running)) },
    )
}
