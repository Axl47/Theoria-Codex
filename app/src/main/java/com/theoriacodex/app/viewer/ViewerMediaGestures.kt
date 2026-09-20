package com.theoriacodex.app.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.viewer.state.ViewerMediaKey

internal data class ViewerMediaGestureActions(
    val seek: (Long) -> Unit,
    val zoom: () -> Unit,
    val toggleControls: () -> Unit,
    val showInfo: () -> Unit,
)

/** Pointer gestures and assistive actions dispatch to the same renderer commands. */
@Composable
internal fun viewerMediaGestureModifier(
    mediaKey: ViewerMediaKey,
    active: Boolean,
    seekable: Boolean,
    zoomed: Boolean,
    chromeVisible: Boolean,
    touchExplorationEnabled: Boolean,
    actions: ViewerMediaGestureActions,
): Modifier {
    val currentActions by rememberUpdatedState(actions)
    val gestures = Modifier.pointerInput(mediaKey, seekable, touchExplorationEnabled) {
        detectTapGestures(
            onDoubleTap = { offset -> currentActions.doubleTap(offset.x, size.width, seekable) },
            onTap = { if (!touchExplorationEnabled) currentActions.toggleControls() },
            onLongPress = { currentActions.showInfo() },
        )
    }
    if (!active) return gestures
    return gestures.semantics {
        contentDescription = "Media actions"
        onClick(label = "Media information") { actions.showInfo(); true }
        customActions = mediaAccessibilityActions(seekable, zoomed, chromeVisible, touchExplorationEnabled, actions)
    }
}

private fun ViewerMediaGestureActions.doubleTap(x: Float, width: Int, seekable: Boolean) {
    val ratio = if (width > 0) x / width.toFloat() else 0.5f
    when {
        seekable && ratio <= 0.35f -> seek(-10_000L)
        seekable && ratio >= 0.65f -> seek(10_000L)
        ratio > 0.35f && ratio < 0.65f -> zoom()
    }
}

private fun mediaAccessibilityActions(
    seekable: Boolean,
    zoomed: Boolean,
    chromeVisible: Boolean,
    touchExplorationEnabled: Boolean,
    actions: ViewerMediaGestureActions,
): List<CustomAccessibilityAction> = buildList {
    if (seekable) {
        add(CustomAccessibilityAction("Rewind 10 seconds") { actions.seek(-10_000L); true })
        add(CustomAccessibilityAction("Forward 10 seconds") { actions.seek(10_000L); true })
    }
    add(CustomAccessibilityAction(if (zoomed) "Reset zoom" else "Zoom in") { actions.zoom(); true })
    if (!touchExplorationEnabled) {
        add(CustomAccessibilityAction(if (chromeVisible) "Hide controls" else "Show controls") {
            actions.toggleControls(); true
        })
    }
}
