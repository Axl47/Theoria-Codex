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

/** A swipe must travel this share of the bar's width to step. */
private const val STEP_FRACTION = 0.22f

/** How far a swipe toward a missing step still moves, so the bar answers without stepping. */
private const val BLOCKED_RESISTANCE = 0.2f

/** Where the next search's text enters from, as a share of the bar's width. */
private const val ENTRY_FRACTION = 0.3f

/**
 * What a sideways swipe on the collapsed search bar can do: swiping right steps back to the previous settled
 * search, swiping left steps forward again.
 */
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

/** The bar's swipe: how far its text has slid, in pixels, and the bar's width the step threshold is a share of. */
@Stable
internal class SearchBarSwipe {
    var offsetPx by mutableFloatStateOf(0f)
    var widthPx by mutableIntStateOf(0)

    /** Slides and fades the summary text with the finger. */
    fun Modifier.followingSwipe(): Modifier = graphicsLayer {
        translationX = offsetPx
        alpha = 1f - (abs(offsetPx) / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.6f
    }

    /** Follows the finger by [delta]; past the start toward a missing step it only gives a little. */
    fun drag(delta: Float, history: SearchBarHistory) {
        val next = offsetPx + delta
        offsetPx = if (history.allows(back = next > 0)) next else offsetPx + delta * BLOCKED_RESISTANCE
    }

    /** Steps when the swipe went far enough toward an available step, then slides the text home. */
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

/**
 * Lets the bar be swiped sideways through [history]. Only a swipe toward an available step claims the gesture once
 * it passes touch slop, so the home pager does not page while the bar steps; a swipe the other way stays the
 * pager's and changes tab. TalkBack gets the same steps as custom actions.
 */
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
