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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import com.theoriacodex.app.ui.motion.TheoriaMotion

/**
 * Where one triage card is dragged to, and where the finger is (window coordinates) while it is down. Each card
 * gets its own, so the card after it starts at rest.
 */
@Stable
internal class TriageDrag {
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

    /** Moves the card to [target], springing when [fly] is false and sliding quickly when it is true. */
    suspend fun moveTo(target: Offset, fly: Boolean) {
        val spec: AnimationSpec<Offset> = if (fly) tween(TheoriaMotion.SHORT_MS) else spring(Spring.DampingRatioLowBouncy)
        animate(Offset.VectorConverter, offset, target, animationSpec = spec) { value, _ -> offset = value }
    }
}

/**
 * Lets a finger drag [drag] around inside this node and reports its release velocity. [windowOrigin] is this
 * node's top-left in the window, which turns the node's pointer positions into window coordinates.
 */
internal fun Modifier.triageSwipe(
    drag: TriageDrag,
    windowOrigin: () -> Offset,
    onRelease: (Velocity) -> Unit,
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
            onRelease(tracker.calculateVelocity())
        },
        onDragCancel = {
            drag.lift()
            onRelease(Velocity.Zero)
        },
    )
}
