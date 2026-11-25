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
import kotlin.math.abs

@Stable
internal class TriageDrag {
    private var releasing: Job? = null

    var offset by mutableStateOf(Offset.Zero)
        private set

    var rowPointer by mutableStateOf<Offset?>(null)
        private set

    val moving by derivedStateOf { offset != Offset.Zero }

    fun dragBy(delta: Offset, to: Offset) {
        offset += delta
        if (abs(offset.y) > abs(offset.x)) rowPointer = to
    }

    fun lift() {
        rowPointer = null
    }

    fun releaseIn(scope: CoroutineScope, release: suspend TriageDrag.() -> Unit) {
        releasing?.cancel()
        releasing = scope.launch { release() }
    }

    fun cancelRelease() {
        releasing?.cancel()
    }

    suspend fun moveTo(target: Offset, fly: Boolean) {
        val spec: AnimationSpec<Offset> = if (fly) tween(TheoriaMotion.SHORT_MS) else spring(Spring.DampingRatioLowBouncy)
        animate(Offset.VectorConverter, offset, target, animationSpec = spec) { value, _ -> offset = value }
    }
}

internal class SwipeThresholds(val commitPx: Float, val flingPx: Float, val cuePx: Float)

internal suspend fun TriageDrag.release(
    velocity: Velocity?,
    thresholds: SwipeThresholds,
    area: Size,
    choose: suspend (SwipeDirection) -> TriageChoice?,
    onDecide: (TriageChoice) -> Unit,
    onSaveElsewhere: () -> Unit,
) {
    val direction = velocity?.let { swipeDirection(offset.x, offset.y, it.x, it.y, thresholds.commitPx, thresholds.flingPx) }
    val choice = direction?.let { choose(it) }
    if (direction == null || choice == null) {
        if (direction != null) onSaveElsewhere()
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

internal fun Modifier.triageSwipe(
    drag: TriageDrag,
    windowOrigin: () -> Offset,
    onEnd: (Velocity?) -> Unit,
): Modifier = pointerInput(drag) {
    val tracker = VelocityTracker()
    detectDragGestures(
        onDragStart = { tracker.resetTracking() },
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
