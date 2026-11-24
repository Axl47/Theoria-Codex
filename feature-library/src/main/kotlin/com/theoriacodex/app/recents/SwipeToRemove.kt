package com.theoriacodex.app.recents

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun SwipeToRemove(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val remove by rememberUpdatedState(onRemove)
    val removeAction = Modifier.removeAction(label) { remove() }
    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val pointer = awaitLeftSwipeStart() ?: return@awaitEachGesture
                    val velocity = VelocityTracker()
                    val finished = horizontalDrag(pointer) { change ->
                        velocity.addPosition(change.uptimeMillis, change.position)
                        val next = (offset.value + change.positionChange().x).coerceAtMost(0f)
                        change.consume()
                        scope.launch { offset.snapTo(next) }
                    }
                    val width = size.width.toFloat()
                    val flung = velocity.calculateVelocity().x < -FLING_VELOCITY
                    scope.launch {
                        if (finished && (offset.value < -width * REMOVE_FRACTION || flung)) {
                            offset.animateTo(-width)
                            remove()
                            delay(RETURN_DELAY_MS)
                        }
                        offset.animateTo(0f)
                    }
                }
            },
    ) {
        if (offset.value < 0f) RemoveBackdrop(Modifier.matchParentSize())
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content(removeAction) }
    }
}

internal fun Modifier.removeAction(label: String, onRemove: () -> Unit): Modifier =
    semantics { customActions = listOf(CustomAccessibilityAction(label) { onRemove(); true }) }

private suspend fun AwaitPointerEventScope.awaitLeftSwipeStart(): PointerId? {
    val down = awaitFirstDown(requireUnconsumed = false)
    var travel = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (change.isConsumed || !change.pressed) return null
        travel += change.positionChange()
        if (travel.getDistance() > viewConfiguration.touchSlop) {
            if (travel.x >= 0f || abs(travel.x) <= abs(travel.y)) return null
            change.consume()
            return change.id
        }
    }
}

@Composable
private fun RemoveBackdrop(modifier: Modifier) {
    Box(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 20.dp),
        )
    }
}

private const val REMOVE_FRACTION = 0.4f
private const val FLING_VELOCITY = 1_500f
private const val RETURN_DELAY_MS = 1_000L
