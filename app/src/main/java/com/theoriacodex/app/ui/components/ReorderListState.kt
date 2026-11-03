package com.theoriacodex.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shared draft, drag thresholds and accessible moves for compact reorder lists. */
internal class ReorderListState<T>(initialItems: List<T>, private val key: (T) -> String) {
    var reorderMode by mutableStateOf(false)
    var reorderDraft by mutableStateOf(initialItems)
    var draggingId by mutableStateOf<String?>(null)
    var draggingIndex by mutableIntStateOf(-1)
    var dragOffsetY by mutableFloatStateOf(0f)
    var itemHeightPx by mutableFloatStateOf(1f)
    /** Gap between rows, so a swap moves the dragged row by exactly one layout step. */
    var itemSpacingPx by mutableFloatStateOf(0f)
    private var dropJob: Job? = null

    fun synchronize(items: List<T>) {
        if (!reorderMode) {
            reorderDraft = items
            return
        }
        val current = reorderDraft.mapNotNull { draft ->
            items.firstOrNull { item -> key(item) == key(draft) }
        }
        reorderDraft = current + items.filterNot { item ->
            current.any { existing -> key(existing) == key(item) }
        }
    }

    fun toggle(items: List<T>, onCommitReorder: (List<String>) -> Unit) {
        if (!reorderMode) {
            reorderDraft = items
            reorderMode = true
            return
        }
        onCommitReorder(reorderDraft.map(key))
        resetDrag()
        reorderMode = false
    }

    fun startDrag(id: String, index: Int) {
        dropJob?.cancel()
        draggingId = id
        draggingIndex = index
        dragOffsetY = 0f
    }

    fun move(id: String, offset: Int): Boolean {
        if (!reorderMode) return false
        val fromIndex = reorderDraft.indexOfFirst { key(it) == id }
        val toIndex = fromIndex + offset
        if (fromIndex !in reorderDraft.indices || toIndex !in reorderDraft.indices || offset == 0) return false
        resetDrag()
        reorderDraft = moveReorderItem(reorderDraft, fromIndex, toIndex)
        return true
    }

    /** Applies a finger or auto-scroll delta; returns whether the row crossed into a new position. */
    fun drag(id: String, fallbackIndex: Int, deltaY: Float): Boolean {
        if (draggingId != id) return false
        dragOffsetY += deltaY
        val currentIndex = draggingIndex.takeIf { it >= 0 } ?: fallbackIndex
        val threshold = itemHeightPx * 0.55f
        val step = itemHeightPx + itemSpacingPx
        return when {
            dragOffsetY >= threshold && currentIndex < reorderDraft.lastIndex -> {
                reorderDraft = moveReorderItem(reorderDraft, currentIndex, currentIndex + 1)
                draggingIndex = currentIndex + 1
                dragOffsetY -= step
                true
            }
            dragOffsetY <= -threshold && currentIndex > 0 -> {
                reorderDraft = moveReorderItem(reorderDraft, currentIndex, currentIndex - 1)
                draggingIndex = currentIndex - 1
                dragOffsetY += step
                true
            }
            else -> false
        }
    }

    /** Glides the released row into its slot before it stops being the dragged row. */
    fun drop(scope: CoroutineScope) {
        dropJob?.cancel()
        dropJob = scope.launch {
            animate(dragOffsetY, 0f, animationSpec = TheoriaMotion.recoil) { value, _ -> dragOffsetY = value }
            resetDrag()
        }
    }

    fun resetDrag() {
        dropJob?.cancel()
        draggingId = null
        draggingIndex = -1
        dragOffsetY = 0f
    }
}

private fun <T> moveReorderItem(items: List<T>, from: Int, to: Int): List<T> =
    items.toMutableList().apply { add(to, removeAt(from)) }

/** Neighbours glide around the dragged row, which instead follows the finger exactly. */
internal fun LazyItemScope.reorderRowPlacement(dragging: Boolean): Modifier = Modifier.animateItem(
    fadeInSpec = null,
    fadeOutSpec = null,
    placementSpec = if (dragging) null else spring<IntOffset>(dampingRatio = 1f, stiffness = 550f),
)

private val REORDER_EDGE_ZONE = 72.dp
private val REORDER_MAX_SCROLL_PER_FRAME = 14.dp

/** Scrolls the list while the dragged row is near its edges, keeping the row under the finger. */
@Composable
internal fun <T> ReorderAutoScrollEffect(state: ReorderListState<T>, list: LazyListState) {
    val draggingId = state.draggingId ?: return
    val edgeZone = with(LocalDensity.current) { REORDER_EDGE_ZONE.toPx() }
    val maxStep = with(LocalDensity.current) { REORDER_MAX_SCROLL_PER_FRAME.toPx() }
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    LaunchedEffect(draggingId, list, edgeZone) {
        while (true) {
            withFrameNanos { }
            val layout = list.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.key == draggingId } ?: continue
            val top = row.offset + state.dragOffsetY
            val step = reorderAutoScrollStep(
                top, top + row.size, layout.viewportStartOffset.toFloat(), layout.viewportEndOffset.toFloat(), edgeZone, maxStep,
            )
            if (step != 0f) state.dragBy(draggingId, list.scrollBy(step), haptics)
        }
    }
}

/** Speed grows from zero at the zone's inner edge to [maxStep] at the viewport's edge. */
internal fun reorderAutoScrollStep(
    rowTop: Float,
    rowBottom: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeZone: Float,
    maxStep: Float,
): Float {
    val intoTop = (viewportStart + edgeZone - rowTop) / edgeZone
    val intoBottom = (rowBottom - (viewportEnd - edgeZone)) / edgeZone
    return when {
        intoTop > 0f -> -maxStep * intoTop.coerceAtMost(1f)
        intoBottom > 0f -> maxStep * intoBottom.coerceAtMost(1f)
        else -> 0f
    }
}

/** A step ticks whichever input caused it, so dragging and auto-scrolling feel the same. */
internal fun <T> ReorderListState<T>.dragBy(id: String, deltaY: Float, haptics: HapticFeedback) {
    if (drag(id, draggingIndex, deltaY)) haptics.performHapticFeedback(TheoriaHaptics.Step)
}
