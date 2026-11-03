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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import kotlin.math.abs

internal data class ViewerMediaGestureActions(
    val seek: (Long) -> Unit,
    /** Zooms around a tap in the gesture area's coordinates, or the centre when there is none. */
    val zoom: (Offset?) -> Unit,
    val toggleControls: () -> Unit,
    val showInfo: () -> Unit,
    /** When present, a long press holds this until release instead of opening information. */
    val hold: ((Boolean) -> Unit)? = null,
)

/** Pointer gestures and assistive actions dispatch to the same renderer commands. */
@Composable
internal fun Modifier.viewerMediaGestures(
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
    val gestures = pointerInput(mediaKey, seekable, touchExplorationEnabled) {
        detectTapGestures(
            onDoubleTap = { offset -> currentActions.doubleTap(offset, size.width, seekable, currentZoomed) },
            onTap = { if (!touchExplorationEnabled) currentActions.toggleControls() },
            onLongPress = { currentActions.hold?.invoke(true) ?: currentActions.showInfo() },
            onPress = {
                tryAwaitRelease()
                currentActions.hold?.invoke(false)
            },
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
    /** Height kept free at the bottom for the timeline and post strip, which handle their own drags. */
    val reservedBottomPx: Float = 0f,
)

/** Receives one-finger gestures; pager and level ownership stay with the screen. */
internal interface ViewerGestureHandler {
    fun onPress()
    fun onHorizontalDrag(displacement: Float)
    fun onHorizontalRelease(displacement: Float, velocity: Float)
    /** Downward pull from a column that is not adjusting a level. */
    fun onDismissDrag(x: Float, y: Float, height: Float)
    fun onDismissRelease(velocityY: Float)
    fun onInfoSwipe()
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
            when (val end = readViewerGesture(down, gesture) { currentHandler }) {
                is ViewerGestureEnd.Horizontal -> currentHandler.onHorizontalRelease(end.x, end.velocity)
                is ViewerGestureEnd.Dismiss -> { currentHandler.onDismissRelease(end.velocityY); currentHandler.onGestureEnd() }
                ViewerGestureEnd.Info -> { currentHandler.onInfoSwipe(); currentHandler.onGestureEnd() }
                ViewerGestureEnd.None -> currentHandler.onGestureEnd()
            }
        }
    }
}

private sealed interface ViewerGestureEnd {
    data class Horizontal(val x: Float, val velocity: Float) : ViewerGestureEnd
    data class Dismiss(val velocityY: Float) : ViewerGestureEnd
    data object Info : ViewerGestureEnd
    data object None : ViewerGestureEnd
}

private enum class ViewerGestureMode { Pending, Horizontal, Level, Dismiss, Info }

/** The outer columns adjust levels on video; everywhere else a pull closes and a push opens info. */
private fun viewerVerticalMode(column: ViewerGestureColumn, y: Float, config: ViewerGestureConfig): ViewerGestureMode = when {
    config.levels != null && column != ViewerGestureColumn.Center -> ViewerGestureMode.Level
    y > 0f -> ViewerGestureMode.Dismiss
    else -> ViewerGestureMode.Info
}

/** Follows one finger until it lifts and reports how the gesture ended. */
private suspend fun AwaitPointerEventScope.readViewerGesture(
    down: PointerInputChange,
    config: ViewerGestureConfig,
    handler: () -> ViewerGestureHandler,
): ViewerGestureEnd {
    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    val gesture = ViewerGestureTracker(
        column = viewerGestureColumn(down.position.x, size.width),
        config = config,
        handler = handler,
        touchSlop = viewConfiguration.touchSlop,
        levelRange = (size.height * 0.6f).coerceAtLeast(1f),
        height = size.height.toFloat(),
    )
    try {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull()
                ?.takeIf { it.id == down.id } ?: return ViewerGestureEnd.None
            tracker.addPosition(change.uptimeMillis, change.position)
            gesture.follow(change)
            if (!change.pressed) {
                return gesture.end(tracker.calculateVelocity(), VIEWER_INFO_SWIPE_DISTANCE.toPx(), VIEWER_FLING_VELOCITY.toPx())
            }
        }
    } finally {
        config.levels?.end()
    }
}

/** One gesture's direction lock and the handler calls that follow from it. */
private class ViewerGestureTracker(
    private val column: ViewerGestureColumn,
    private val config: ViewerGestureConfig,
    private val handler: () -> ViewerGestureHandler,
    private val touchSlop: Float,
    private val levelRange: Float,
    private val height: Float,
) {
    private var mode = ViewerGestureMode.Pending
    private var x = 0f
    private var y = 0f
    private var atLimit = false

    fun follow(change: PointerInputChange) {
        val delta = change.position - change.previousPosition
        x += delta.x
        y += delta.y
        val locking = mode == ViewerGestureMode.Pending
        if (locking) mode = lockedMode()
        when (mode) {
            ViewerGestureMode.Horizontal -> handler().onHorizontalDrag(x)
            ViewerGestureMode.Level -> followLevel(if (locking) y else delta.y, begin = locking)
            ViewerGestureMode.Dismiss -> handler().onDismissDrag(x, y, height)
            ViewerGestureMode.Info -> Unit
            ViewerGestureMode.Pending -> return
        }
        change.consume()
    }

    private fun lockedMode(): ViewerGestureMode = when {
        abs(x) > touchSlop && abs(x) > abs(y) * 1.25f -> ViewerGestureMode.Horizontal
        abs(y) > touchSlop && abs(y) > abs(x) * 1.25f -> viewerVerticalMode(column, y, config)
        else -> ViewerGestureMode.Pending
    }

    private fun followLevel(deltaY: Float, begin: Boolean) {
        val levels = config.levels ?: return
        if (begin) levels.begin(if (column == ViewerGestureColumn.Leading) ViewerLevelControl.Brightness else ViewerLevelControl.Volume)
        levels.drag(-deltaY / levelRange)
        val limit = levels.isAtLimit()
        if (limit && !atLimit && !begin) handler().onLevelLimit()
        atLimit = limit
    }

    fun end(velocity: Velocity, infoDistance: Float, flingVelocity: Float): ViewerGestureEnd = when (mode) {
        ViewerGestureMode.Horizontal -> ViewerGestureEnd.Horizontal(x, velocity.x)
        ViewerGestureMode.Dismiss -> ViewerGestureEnd.Dismiss(velocity.y)
        ViewerGestureMode.Info ->
            if (y <= -infoDistance || velocity.y <= -flingVelocity) ViewerGestureEnd.Info else ViewerGestureEnd.None
        ViewerGestureMode.Level, ViewerGestureMode.Pending -> ViewerGestureEnd.None
    }
}

private val VIEWER_INFO_SWIPE_DISTANCE = 64.dp

private fun ViewerLevelControls.isAtLimit(): Boolean =
    feedback?.takeIf { it.available }?.fraction?.let { it <= 0f || it >= 1f } == true
