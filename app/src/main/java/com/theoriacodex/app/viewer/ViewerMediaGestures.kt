package com.theoriacodex.app.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import kotlin.math.abs

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

/** One direction lock keeps level adjustments, page navigation, taps, and pinch gestures separate. */
@Composable
internal fun Modifier.viewerMediaSwipe(
    key: ViewerMediaKey,
    zoomed: Boolean,
    mediaCount: Int,
    invertMultiImageScrollDirection: Boolean,
    onNavigate: (ViewerHorizontalSwipeDirection) -> Unit,
    enabled: Boolean = true,
    levelControls: ViewerLevelControls? = null,
    onSwipeProgress: (Float) -> Unit = {},
    onSwipeCancelled: () -> Unit = {},
): Modifier {
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    val currentProgress by rememberUpdatedState(onSwipeProgress)
    val currentCancel by rememberUpdatedState(onSwipeCancelled)
    return pointerInput(key, enabled, zoomed, mediaCount, invertMultiImageScrollDirection, levelControls) {
        if (!enabled || zoomed) return@pointerInput
        var navigating = false
        try {
            awaitEachGesture {
                navigating = false
                val down = awaitFirstDown(requireUnconsumed = false)
                val threshold = maxOf(48f, size.width * 0.12f)
                val control = if (down.position.x < size.width / 2f) ViewerLevelControl.Brightness else ViewerLevelControl.Volume
                val swipe = readViewerSwipe(down.id, threshold, levelControls, control) { displacement ->
                    val reversed = invertMultiImageScrollDirection && mediaCount > 1
                    currentProgress(if (reversed) -displacement else displacement)
                }
                levelControls?.end()
                val direction = swipe.direction(threshold)
                if (direction != null) {
                    navigating = true
                    currentOnNavigate(viewerSwipeDirectionForSetting(direction, mediaCount, invertMultiImageScrollDirection))
                } else currentCancel()
            }
        } finally {
            if (!navigating) currentCancel()
            levelControls?.end()
        }
    }
}

private enum class ViewerSwipeAxis { Pending, Horizontal, Vertical }

private class ViewerSwipeMotion {
    var x = 0f
    var y = 0f
    var axis = ViewerSwipeAxis.Pending
    var released = false

    fun add(delta: Offset, threshold: Float, touchSlop: Float) {
        x += delta.x
        y += delta.y
        if (axis != ViewerSwipeAxis.Pending) return
        axis = when {
            abs(x) > threshold * 0.35f && abs(x) > abs(y) * 1.25f -> ViewerSwipeAxis.Horizontal
            abs(y) > touchSlop && abs(y) > abs(x) * 1.25f -> ViewerSwipeAxis.Vertical
            else -> ViewerSwipeAxis.Pending
        }
    }

    fun direction(threshold: Float): ViewerHorizontalSwipeDirection? {
        if (!released || axis != ViewerSwipeAxis.Horizontal || abs(x) < threshold) return null
        return if (x < 0f) ViewerHorizontalSwipeDirection.Next else ViewerHorizontalSwipeDirection.Previous
    }
}

private suspend fun AwaitPointerEventScope.readViewerSwipe(
    id: PointerId,
    threshold: Float,
    levels: ViewerLevelControls?,
    control: ViewerLevelControl,
    onHorizontalProgress: (Float) -> Unit,
): ViewerSwipeMotion {
    val swipe = ViewerSwipeMotion()
    var adjusting = false
    while (true) {
        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
        val change = event.changes.singleOrNull()?.takeIf { it.id == id } ?: break
        val delta = change.position - change.previousPosition
        swipe.add(delta, threshold, viewConfiguration.touchSlop)
        if (swipe.axis == ViewerSwipeAxis.Vertical && levels != null) {
            if (!adjusting) levels.begin(control)
            levels.drag(-(if (adjusting) delta.y else swipe.y) / (size.height * 0.6f).coerceAtLeast(1f))
            adjusting = true
            change.consume()
        } else if (swipe.axis == ViewerSwipeAxis.Horizontal) {
            onHorizontalProgress(swipe.x)
            change.consume()
        }
        if (!change.pressed) {
            swipe.released = true
            break
        }
    }
    return swipe
}
