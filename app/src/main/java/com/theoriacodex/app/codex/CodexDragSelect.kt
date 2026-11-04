package com.theoriacodex.app.codex

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import com.theoriacodex.app.ui.components.reorderAutoScrollStep
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.domain.model.PostId
import kotlin.math.max
import kotlin.math.min

internal fun CodexEditSelection.painted(
    base: Set<PostId>,
    posts: List<PostId>,
    anchor: Int,
    current: Int,
    selecting: Boolean,
): CodexEditSelection {
    if (!active) return this
    val range = posts.subList(min(anchor, current), max(anchor, current) + 1)
    return copy(selectedPostIds = if (selecting) base + range else base - range.toSet())
}

/** One long-press drag: the first post decides whether the whole range is selected or cleared. */
private class CodexPaint(val base: Set<PostId>, val anchor: Int, val selecting: Boolean) {
    var current = anchor
}

/** Turns pointer positions over the grid into a painted selection range. */
private class CodexDragPainter(
    private val grid: LazyStaggeredGridState,
    posts: State<List<PostId>>,
    selection: State<CodexEditSelection>,
    onSelectionChange: State<(CodexEditSelection) -> Unit>,
    private val haptics: State<HapticFeedback>,
) {
    private val posts by posts
    private val selection by selection
    private val onSelectionChange by onSelectionChange
    private var paint by mutableStateOf<CodexPaint?>(null)
    var pointer by mutableStateOf<Offset?>(null)
        private set
    val painting: Boolean get() = paint != null

    private fun indexAt(position: Offset): Int? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        Rect(it.offset.toOffset(), it.size.toSize()).contains(position)
    }?.index?.takeIf { it in posts.indices }

    fun begin(position: Offset): Boolean {
        val anchor = indexAt(position) ?: return false
        val base = selection.selectedPostIds
        val started = CodexPaint(base, anchor, posts[anchor] !in base)
        paint = started
        pointer = position
        onSelectionChange(selection.painted(base, posts, anchor, anchor, started.selecting))
        return true
    }

    fun extend(position: Offset) {
        pointer = position
        val active = paint ?: return
        val index = indexAt(position) ?: return
        if (index == active.current) return
        active.current = index
        haptics.value.performHapticFeedback(TheoriaHaptics.Step)
        onSelectionChange(selection.painted(active.base, posts, active.anchor, index, active.selecting))
    }

    fun end() {
        paint = null
        pointer = null
    }

    /** Scrolls when the finger nears an edge, then selects whatever slid under it. */
    suspend fun autoScroll(edgeZone: Float, maxStep: Float) {
        val position = pointer ?: return
        val layout = grid.layoutInfo
        val step = reorderAutoScrollStep(
            position.y, position.y, layout.viewportStartOffset.toFloat(), layout.viewportEndOffset.toFloat(), edgeZone, maxStep,
        )
        if (step == 0f) return
        grid.scrollBy(step)
        extend(position)
    }
}

private val PAINT_EDGE_ZONE = 72.dp
private val PAINT_MAX_SCROLL_PER_FRAME = 14.dp

/** While [selection] is active, long-press and drag across the grid to select a range, scrolling at its edges. */
@Composable
internal fun Modifier.codexDragSelect(
    grid: LazyStaggeredGridState,
    posts: List<PostId>,
    selection: CodexEditSelection,
    onSelectionChange: (CodexEditSelection) -> Unit,
): Modifier {
    val postsState = rememberUpdatedState(posts)
    val selectionState = rememberUpdatedState(selection)
    val changeState = rememberUpdatedState(onSelectionChange)
    val hapticsState = rememberUpdatedState(LocalHapticFeedback.current)
    val painter = remember(grid) { CodexDragPainter(grid, postsState, selectionState, changeState, hapticsState) }
    val edgeZone = with(LocalDensity.current) { PAINT_EDGE_ZONE.toPx() }
    val maxStep = with(LocalDensity.current) { PAINT_MAX_SCROLL_PER_FRAME.toPx() }
    LaunchedEffect(painter, painter.painting) {
        while (painter.painting) {
            withFrameNanos { }
            painter.autoScroll(edgeZone, maxStep)
        }
    }
    if (!selection.active) return this
    return pointerInput(painter) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!painter.begin(hold.position)) return@awaitEachGesture
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    painter.extend(change.position)
                }
            } finally {
                painter.end()
            }
        }
    }
}
