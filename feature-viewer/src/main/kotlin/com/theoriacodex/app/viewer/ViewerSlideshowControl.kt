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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.theoriacodex.app.viewer.state.VIEWER_SLIDESHOW_IMAGE_MS
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.holdViewerSlideshowImage
import com.theoriacodex.app.viewer.state.slideshowImageHold
import kotlinx.coroutines.launch

@Composable
internal fun BoxScope.ViewerSlideshowControl(frame: ViewerFrame, suppressed: Boolean, inStripRow: Boolean, onAction: (ViewerAction) -> Unit) {
    val state = frame.uiState
    val running = state.controls.slideshow
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val hold = state.slideshowImageHold().takeIf { resumed }
    val currentState by rememberUpdatedState(state)
    val currentOnAction by rememberUpdatedState(onAction)
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
        contentDescription = if (running) "Stop slideshow" else "Start slideshow", testTag = "viewer-slideshow", inStripRow = inStripRow,
        onClick = { onAction(ViewerAction.SetSlideshow(!running)) },
    )
}
