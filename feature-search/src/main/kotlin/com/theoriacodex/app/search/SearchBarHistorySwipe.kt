package com.theoriacodex.app.search

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
    val enabled: Boolean get() = canBack || canForward

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
}

/**
 * Lets the bar be swiped sideways through [history]. The drag claims the gesture once it passes touch slop, so the
 * home pager does not page while the bar is swiped. TalkBack gets the same steps as custom actions.
 */
@Composable
internal fun Modifier.searchBarHistorySwipe(history: SearchBarHistory, swipe: SearchBarSwipe): Modifier {
    val current by rememberUpdatedState(history)
    val dragState = rememberDraggableState { delta ->
        val next = swipe.offsetPx + delta
        swipe.offsetPx = if (current.allows(back = next > 0)) next else swipe.offsetPx + delta * BLOCKED_RESISTANCE
    }
    val actions = remember(history) {
        listOfNotNull(
            CustomAccessibilityAction("Previous search") { history.onStep(true); true }.takeIf { history.canBack },
            CustomAccessibilityAction("Next search") { history.onStep(false); true }.takeIf { history.canForward },
        )
    }
    return onSizeChanged { swipe.widthPx = it.width }
        .semantics { customActions = actions }
        .draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            enabled = history.enabled,
            onDragStopped = {
                val travelled = swipe.offsetPx
                val back = travelled > 0
                if (current.allows(back) && abs(travelled) > swipe.widthPx * STEP_FRACTION) {
                    current.onStep(back)
                    swipe.offsetPx = -travelled.sign * swipe.widthPx * ENTRY_FRACTION
                }
                animate(swipe.offsetPx, 0f) { value, _ -> swipe.offsetPx = value }
            },
        )
}
