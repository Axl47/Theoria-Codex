package com.theoriacodex.app.codex

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.toOffset
import com.theoriacodex.app.ui.components.reorderAutoScrollStep
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class CodexGridReorderState(private val grid: LazyGridState, private val isFixed: (String) -> Boolean) {
    var order by mutableStateOf<List<String>?>(null)
        private set

    var liftedKey by mutableStateOf<String?>(null)
        private set

    var dragging by mutableStateOf(false)
        private set

    private var finger by mutableStateOf(Offset.Zero)
    private var settleOffset by mutableStateOf(Offset.Zero)
    private var liftedFrom = Offset.Zero
    private var pickedSize = IntSize.Zero
    private var pickedOrder: List<String> = emptyList()
    private var travel = 0f
    private var swapped = false
    private var settleJob: Job? = null
    private var shown: List<String> = emptyList()

    fun bind(presented: List<String>, enabled: Boolean) {
        shown = presented
        val pending = order ?: return
        val pendingIds = pending.filterNot(isFixed)
        val shownIds = presented.filterNot(isFixed)
        when {
            !enabled || pendingIds.toSet() != shownIds.toSet() -> abandon()
            !dragging && pendingIds == shownIds -> order = null
            else -> order = displayed(presented)
        }
    }

    fun displayed(presented: List<String>): List<String> {
        val pending = order?.takeIf { it.filterNot(isFixed).toSet() == presented.filterNot(isFixed).toSet() }
        return pending?.filter { !isFixed(it) || it in presented } ?: presented
    }

    fun commitFailed(failed: List<String>) {
        if (dragging) {
            if (pickedOrder == failed) pickedOrder = shown
        } else if (order == failed) {
            order = null
        }
    }

    fun translationOf(key: String): Offset = when {
        key != liftedKey -> Offset.Zero
        dragging -> cell(key)?.let { finger - it.offset.toOffset() } ?: Offset.Zero
        else -> settleOffset
    }

    fun pickUp(position: Offset): String? {
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(position) } ?: return null
        val key = (item.key as? String)?.takeUnless(isFixed) ?: return null
        settleJob?.cancel()
        pickedOrder = order ?: shown
        order = pickedOrder
        liftedKey = key
        dragging = true
        finger = item.offset.toOffset()
        liftedFrom = finger
        pickedSize = item.size
        travel = 0f
        swapped = false
        return key
    }

    fun dragBy(delta: Offset, haptics: HapticFeedback) {
        if (!dragging) return
        finger += delta
        travel = maxOf(travel, (finger - liftedFrom).getDistance())
        swapUnderCentre(haptics)
    }

    fun release(scope: CoroutineScope, touchSlop: Float, commit: (List<String>) -> Unit): String? {
        val key = liftedKey?.takeIf { dragging } ?: return null
        val from = translationOf(key)
        dragging = false
        val next = order
        if (swapped && next != null && next != pickedOrder) commit(next) else order = pickedOrder.takeUnless { it == shown }
        settle(scope, from)
        return key.takeIf { !swapped && travel <= touchSlop }
    }

    fun cancel() {
        if (!dragging) return
        dragging = false
        liftedKey = null
        order = pickedOrder.takeUnless { it == shown }
    }

    fun canStep(presented: List<String>, key: String, step: Int): Boolean = !dragging && step(presented, key, step) != null

    fun stepAndCommit(presented: List<String>, key: String, step: Int, commit: (List<String>) -> Unit): Boolean {
        val next = step(presented, key, step)?.takeIf { !dragging } ?: return false
        order = next
        commit(next)
        return true
    }

    suspend fun autoScroll(edgeZone: Float, maxStep: Float, haptics: HapticFeedback) {
        while (dragging) {
            withFrameNanos { }
            val layout = grid.layoutInfo
            val step = reorderAutoScrollStep(
                finger.y, finger.y + pickedSize.height,
                layout.viewportStartOffset.toFloat(), layout.viewportEndOffset.toFloat(), edgeZone, maxStep,
            )
            if (step != 0f && dragging) {
                grid.scrollBy(step)
                swapUnderCentre(haptics)
            }
        }
    }

    private fun step(presented: List<String>, key: String, step: Int): List<String>? {
        val current = displayed(presented)
        return stepTile(current, key, step, current.filter(isFixed).toSet())
    }

    private fun swapUnderCentre(haptics: HapticFeedback) {
        val key = liftedKey ?: return
        val current = order ?: return
        val visible = grid.layoutInfo.visibleItemsInfo
        if (visible.any { current.getOrNull(it.index) != it.key }) return
        val centre = finger + pickedSize.center.toOffset()
        val target = visible.firstOrNull { it.key != key && it.contains(centre) }?.key as? String ?: return
        val next = moveTile(current, key, target, current.filter(isFixed).toSet())
        if (next == current) return
        val first = grid.firstVisibleItemIndex
        if (first == current.indexOf(key) || first == current.indexOf(target)) {
            grid.requestScrollToItem(first, grid.firstVisibleItemScrollOffset)
        }
        order = next
        swapped = true
        haptics.performHapticFeedback(TheoriaHaptics.Step)
    }

    private fun settle(scope: CoroutineScope, from: Offset) {
        settleOffset = from
        settleJob = scope.launch {
            animate(Offset.VectorConverter, from, Offset.Zero, animationSpec = SETTLE_SPEC) { value, _ -> settleOffset = value }
            liftedKey = null
        }
    }

    private fun abandon() {
        settleJob?.cancel()
        order = null
        liftedKey = null
        dragging = false
    }

    private fun cell(key: String): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
}

private fun LazyGridItemInfo.contains(point: Offset): Boolean =
    point.x >= offset.x && point.x < offset.x + size.width && point.y >= offset.y && point.y < offset.y + size.height

private val SETTLE_SPEC = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, Offset.VisibilityThreshold)

internal fun Modifier.codexReorderGestures(
    state: CodexGridReorderState,
    haptics: HapticFeedback,
    scope: CoroutineScope,
    onCommit: (List<String>) -> Unit,
    onOpenActions: (String) -> Unit,
): Modifier = pointerInput(state, haptics, scope) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        state.pickUp(press.position) ?: return@awaitEachGesture
        haptics.performHapticFeedback(TheoriaHaptics.Hold)
        var lifted = false
        try {
            lifted = followFinger(press.id, state, haptics)
        } finally {
            if (lifted) state.release(scope, viewConfiguration.touchSlop, onCommit)?.let(onOpenActions) else state.cancel()
        }
    }
}

private suspend fun AwaitPointerEventScope.followFinger(id: PointerId, state: CodexGridReorderState, haptics: HapticFeedback): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return false
        val delta = change.positionChangeIgnoreConsumed()
        change.consume()
        if (!change.pressed) return true
        state.dragBy(delta, haptics)
    }
}
