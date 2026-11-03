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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import kotlin.math.abs

internal data class ViewerMediaGestureActions(
    val seek: (Long) -> Unit,
    /** Zooms around a tap in the gesture area's coordinates, or the centre when there is none. */
    val zoom: (Offset?) -> Unit,
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
    val currentZoomed by rememberUpdatedState(zoomed)
    val gestures = Modifier.pointerInput(mediaKey, seekable, touchExplorationEnabled) {
        detectTapGestures(
            onDoubleTap = { offset -> currentActions.doubleTap(offset, size.width, seekable, currentZoomed) },
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

/** The Viewer's one spatial map: double-tap and vertical swipes both read these columns. */
internal enum class ViewerGestureColumn { Leading, Center, Trailing }

internal fun viewerGestureColumn(x: Float, width: Int): ViewerGestureColumn {
    val ratio = if (width > 0) x / width.toFloat() else 0.5f
    return when {
        ratio <= 0.35f -> ViewerGestureColumn.Leading
        ratio >= 0.65f -> ViewerGestureColumn.Trailing
        else -> ViewerGestureColumn.Center
    }
}

/** Only seekable media reserves the outer columns; zoomed media always double-taps back to fit. */
private fun ViewerMediaGestureActions.doubleTap(offset: Offset, width: Int, seekable: Boolean, zoomed: Boolean) {
    if (zoomed || !seekable) return zoom(offset)
    when (viewerGestureColumn(offset.x, width)) {
        ViewerGestureColumn.Leading -> seek(-10_000L)
        ViewerGestureColumn.Center -> zoom(offset)
        ViewerGestureColumn.Trailing -> seek(10_000L)
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
    add(CustomAccessibilityAction(if (zoomed) "Reset zoom" else "Zoom in") { actions.zoom(null); true })
    if (!touchExplorationEnabled) {
        add(CustomAccessibilityAction(if (chromeVisible) "Hide controls" else "Show controls") {
            actions.toggleControls(); true
        })
    }
}

/** What the Viewer's recognizer may do for the gesture that is starting. */
internal data class ViewerGestureConfig(
    val enabled: Boolean,
    val levels: ViewerLevelControls? = null,
    /** Height kept free at the bottom for the timeline, which handles its own drags. */
    val reservedBottomPx: Float = 0f,
)

/** Receives one-finger gestures; pager and level ownership stay with the screen. */
internal interface ViewerGestureHandler {
    fun onPress()
    fun onHorizontalDrag(displacement: Float)
    fun onHorizontalRelease(displacement: Float, velocity: Float)
    /** The gesture ended without a horizontal release: a tap, a vertical drag, or a second finger. */
    fun onGestureEnd()
    fun onLevelLimit()
}

/**
 * The Viewer's single recognizer. It sits above the pagers so a finger keeps its gesture even when
 * the page it landed on slides away, and one direction lock keeps level adjustments, navigation,
 * taps, and pinches separate. It reads [config] per gesture, so selection changes cannot restart it.
 */
@Composable
internal fun Modifier.viewerGestures(config: ViewerGestureConfig, handler: ViewerGestureHandler): Modifier {
    val currentConfig by rememberUpdatedState(config)
    val currentHandler by rememberUpdatedState(handler)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val gesture = currentConfig
            if (!gesture.enabled || down.position.y > size.height - gesture.reservedBottomPx) return@awaitEachGesture
            currentHandler.onPress()
            val release = readViewerGesture(down, gesture) { currentHandler }
            if (release != null) currentHandler.onHorizontalRelease(release.x, release.velocity)
            else currentHandler.onGestureEnd()
        }
    }
}

private class ViewerHorizontalRelease(val x: Float, val velocity: Float)

private enum class ViewerGestureMode { Pending, Horizontal, Level, Ignored }

private fun viewerVerticalMode(column: ViewerGestureColumn, config: ViewerGestureConfig): ViewerGestureMode =
    if (config.levels != null) ViewerGestureMode.Level else ViewerGestureMode.Ignored

/** Follows one finger until it lifts; returns the horizontal release, if that is what it was. */
private suspend fun AwaitPointerEventScope.readViewerGesture(
    down: PointerInputChange,
    config: ViewerGestureConfig,
    handler: () -> ViewerGestureHandler,
): ViewerHorizontalRelease? {
    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    val column = viewerGestureColumn(down.position.x, size.width)
    val control = if (down.position.x < size.width / 2f) ViewerLevelControl.Brightness else ViewerLevelControl.Volume
    var mode = ViewerGestureMode.Pending
    var x = 0f
    var y = 0f
    var atLimit = false
    try {
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.singleOrNull()?.takeIf { it.id == down.id } ?: return null
            val delta = change.position - change.previousPosition
            x += delta.x
            y += delta.y
            tracker.addPosition(change.uptimeMillis, change.position)
            if (mode == ViewerGestureMode.Pending) {
                val slop = viewConfiguration.touchSlop
                mode = when {
                    abs(x) > slop && abs(x) > abs(y) * 1.25f -> ViewerGestureMode.Horizontal
                    abs(y) > slop && abs(y) > abs(x) * 1.25f -> viewerVerticalMode(column, config).also {
                        if (it == ViewerGestureMode.Level) config.levels?.begin(control)
                    }
                    else -> ViewerGestureMode.Pending
                }
                if (mode == ViewerGestureMode.Level) {
                    config.levels?.drag(-y / levelDragRange())
                    atLimit = config.levels.isAtLimit()
                    change.consume()
                    if (!change.pressed) return null
                    continue
                }
            }
            when (mode) {
                ViewerGestureMode.Horizontal -> { handler().onHorizontalDrag(x); change.consume() }
                ViewerGestureMode.Level -> {
                    config.levels?.drag(-delta.y / levelDragRange())
                    val limit = config.levels.isAtLimit()
                    if (limit && !atLimit) handler().onLevelLimit()
                    atLimit = limit
                    change.consume()
                }
                ViewerGestureMode.Ignored, ViewerGestureMode.Pending -> Unit
            }
            if (!change.pressed) {
                return if (mode == ViewerGestureMode.Horizontal) ViewerHorizontalRelease(x, tracker.calculateVelocity().x) else null
            }
        }
    } finally {
        config.levels?.end()
    }
}

private fun AwaitPointerEventScope.levelDragRange(): Float = (size.height * 0.6f).coerceAtLeast(1f)

private fun ViewerLevelControls?.isAtLimit(): Boolean =
    this?.feedback?.takeIf { it.available }?.fraction?.let { it <= 0f || it >= 1f } == true
