package com.theoriacodex.app.search

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private const val STEP_FRACTION = 0.22f

private const val BLOCKED_RESISTANCE = 0.2f

private const val ENTRY_FRACTION = 0.3f

@Immutable
internal class SearchBarHistory(
    val canBack: Boolean,
    val canForward: Boolean,
    val onStep: (back: Boolean) -> Unit,
) {
    fun allows(back: Boolean): Boolean = if (back) canBack else canForward

    companion object {
        val None = SearchBarHistory(canBack = false, canForward = false) {}
    }
}

@Stable
internal class SearchBarSwipe {
    var offsetPx by mutableFloatStateOf(0f)
    var widthPx by mutableIntStateOf(0)

    fun Modifier.followingSwipe(): Modifier = graphicsLayer {
        translationX = offsetPx
        alpha = 1f - (abs(offsetPx) / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.6f
    }

    fun drag(delta: Float, history: SearchBarHistory) {
        val next = offsetPx + delta
        offsetPx = if (history.allows(back = next > 0)) next else offsetPx + delta * BLOCKED_RESISTANCE
    }

    suspend fun release(history: SearchBarHistory) {
        val travelled = offsetPx
        val back = travelled > 0
        if (history.allows(back) && abs(travelled) > widthPx * STEP_FRACTION) {
            history.onStep(back)
            offsetPx = -travelled.sign * widthPx * ENTRY_FRACTION
        }
        animate(offsetPx, 0f) { value, _ -> offsetPx = value }
    }
}

@Composable
internal fun Modifier.searchBarHistorySwipe(history: SearchBarHistory, swipe: SearchBarSwipe): Modifier {
    val current by rememberUpdatedState(history)
    val scope = rememberCoroutineScope()
    val actions = remember(history) {
        listOfNotNull(
            CustomAccessibilityAction("Previous search") { history.onStep(true); true }.takeIf { history.canBack },
            CustomAccessibilityAction("Next search") { history.onStep(false); true }.takeIf { history.canForward },
        )
    }
    return onSizeChanged { swipe.widthPx = it.width }
        .semantics { customActions = actions }
        .pointerInput(swipe) {
            var settling: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    if (current.allows(back = overSlop > 0)) {
                        change.consume()
                        settling?.cancel()
                        swipe.drag(overSlop, current)
                    }
                } ?: return@awaitEachGesture
                horizontalDrag(start.id) { change ->
                    swipe.drag(change.positionChange().x, current)
                    change.consume()
                }
                settling = scope.launch { swipe.release(current) }
            }
        }
}
