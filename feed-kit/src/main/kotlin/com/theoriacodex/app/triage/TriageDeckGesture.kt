package com.theoriacodex.app.triage

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where one triage card is dragged to, where the finger is (window coordinates) while it is down, and the release
 * still deciding where the card goes. Each card gets its own, so the card after it starts at rest.
 */
@Stable
internal class TriageDrag {
    private var releasing: Job? = null

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The finger's position in the window, or null once it lifts. */
    var pointer by mutableStateOf<Offset?>(null)
        private set

    /** The card is away from rest, so what is behind it can show. */
    val moving by derivedStateOf { offset != Offset.Zero }

    fun start(at: Offset) {
        pointer = at
    }

    fun dragBy(delta: Offset, to: Offset) {
        offset += delta
        pointer = to
    }

    fun lift() {
        pointer = null
    }

    /** Runs [release] in [scope] as this card's one release: a new release replaces one still running. */
    fun releaseIn(scope: CoroutineScope, release: suspend TriageDrag.() -> Unit) {
        releasing?.cancel()
        releasing = scope.launch { release() }
    }

    /** Stops a release still running, as when the card leaves the deck. */
    fun cancelRelease() {
        releasing?.cancel()
    }

    /** Moves the card to [target], springing when [fly] is false and sliding quickly when it is true. */
    suspend fun moveTo(target: Offset, fly: Boolean) {
        val spec: AnimationSpec<Offset> = if (fly) tween(TheoriaMotion.SHORT_MS) else spring(Spring.DampingRatioLowBouncy)
        animate(Offset.VectorConverter, offset, target, animationSpec = spec) { value, _ -> offset = value }
    }
}

/** What a swipe must travel or reach to commit, and how far a vertical pull must go before its targets show. */
internal class SwipeThresholds(val commitPx: Float, val flingPx: Float, val cuePx: Float)

/**
 * What a finished drag does. Released ([velocity] non-null) in a direction [choose] gives a choice for, the card
 * flies off and [onDecide] commits it; released toward a save with nowhere to save, it springs back and
 * [onNothingToSaveTo] runs. Anything else springs back, and so does every cancelled drag ([velocity] null), however
 * far it went: only a release commits. [choose] may wait, and the card stays where it was let go meanwhile.
 */
internal suspend fun TriageDrag.release(
    velocity: Velocity?,
    thresholds: SwipeThresholds,
    area: Size,
    choose: suspend (SwipeDirection) -> TriageChoice?,
    onDecide: (TriageChoice) -> Unit,
    onNothingToSaveTo: () -> Unit,
) {
    val direction = velocity?.let { swipeDirection(offset.x, offset.y, it.x, it.y, thresholds.commitPx, thresholds.flingPx) }
    val choice = direction?.let { choose(it) }
    if (direction == null || choice == null) {
        if (direction != null) onNothingToSaveTo()
        moveTo(Offset.Zero, fly = false)
        return
    }
    val exit = when (direction) {
        SwipeDirection.Right -> Offset(area.width, offset.y)
        SwipeDirection.Left -> Offset(-area.width, offset.y)
        SwipeDirection.Up -> Offset(offset.x, -area.height)
        SwipeDirection.Down -> Offset(offset.x, area.height)
    }
    moveTo(exit, fly = true)
    onDecide(choice)
}

/**
 * Lets a finger drag [drag] around inside this node and reports how it ended: the release velocity, or null when
 * the gesture was cancelled. [windowOrigin] is this node's top-left in the window, which turns the node's pointer
 * positions into window coordinates.
 */
internal fun Modifier.triageSwipe(
    drag: TriageDrag,
    windowOrigin: () -> Offset,
    onEnd: (Velocity?) -> Unit,
): Modifier = pointerInput(drag) {
    val tracker = VelocityTracker()
    detectDragGestures(
        onDragStart = { start ->
            tracker.resetTracking()
            drag.start(windowOrigin() + start)
        },
        onDrag = { change, amount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            drag.dragBy(amount, windowOrigin() + change.position)
        },
        onDragEnd = {
            drag.lift()
            onEnd(tracker.calculateVelocity())
        },
        onDragCancel = {
            drag.lift()
            onEnd(null)
        },
    )
}
