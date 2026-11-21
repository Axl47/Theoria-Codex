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

/**
 * Long-press drag reordering for the Library's Codex grid under Manual sort.
 *
 * The gesture runs on the grid ([codexReorderGestures]), not on a tile, so a tile the lazy grid disposes mid-drag
 * never cancels it. The lifted tile follows the finger; when its centre crosses another movable tile it takes that
 * tile's place in [order], and the others glide there through `animateItem`. Keys for which [isFixed] holds (a
 * Codex waiting to be deleted) never move. The grid binds what it presents through [bind] on every composition, and
 * reports a commit that failed through [commitFailed].
 */
@Stable
internal class CodexGridReorderState(private val grid: LazyGridState, private val isFixed: (String) -> Boolean) {
    /** What the grid shows while a drag or its commit is pending; null shows the presented order. */
    var order by mutableStateOf<List<String>?>(null)
        private set

    /** The lifted tile, kept while it settles into its cell so it stays drawn above its neighbours. */
    var liftedKey by mutableStateOf<String?>(null)
        private set

    var dragging by mutableStateOf(false)
        private set

    // Where the lifted tile's top-left belongs, in viewport coordinates; scrolling moves cells, not the finger.
    private var finger by mutableStateOf(Offset.Zero)
    private var settleOffset by mutableStateOf(Offset.Zero)
    private var liftedFrom = Offset.Zero
    private var pickedSize = IntSize.Zero
    private var pickedOrder: List<String> = emptyList()
    private var travel = 0f
    private var swapped = false
    private var settleJob: Job? = null
    private var shown: List<String> = emptyList()

    /**
     * Takes the presented keys after each composition. A commit that arrived, a Codex that came or went, or
     * reordering turning off ([enabled] false: an automatic sort) ends the pending order.
     */
    fun bind(presented: List<String>, enabled: Boolean) {
        shown = presented
        val pending = order ?: return
        val pendingIds = pending.filterNot(isFixed)
        val shownIds = presented.filterNot(isFixed)
        when {
            !enabled || pendingIds.toSet() != shownIds.toSet() -> abandon()
            !dragging && pendingIds == shownIds -> order = null
            // A fixed cell that went would leave the pending order out of step with the grid.
            else -> order = displayed(presented)
        }
    }

    /** The keys to lay out: the pending order while it holds the [presented] Codices, less a fixed cell since gone. */
    fun displayed(presented: List<String>): List<String> {
        val pending = order?.takeIf { it.filterNot(isFixed).toSet() == presented.filterNot(isFixed).toSet() }
        return pending?.filter { !isFixed(it) || it in presented } ?: presented
    }

    /**
     * A commit of [failed] did not land, so the grid would keep showing an order that was never saved: drop the
     * pending order while it is still that one. A newer drag, or a newer order waiting on its own commit, stays.
     */
    fun commitFailed(failed: List<String>) {
        if (!dragging && order == failed) order = null
    }

    /** Where tile [key] is drawn relative to its cell. */
    fun translationOf(key: String): Offset = when {
        key != liftedKey -> Offset.Zero
        dragging -> cell(key)?.let { finger - it.offset.toOffset() } ?: Offset.Zero
        else -> settleOffset
    }

    /** Lifts the movable tile under [position]; returns its key, or null when there is none. */
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

    /**
     * Ends the drag: commits a changed order through [commit] and settles the tile into its cell. Returns the key
     * whose menu to open when the tile was released where it was picked up.
     */
    fun release(scope: CoroutineScope, touchSlop: Float, commit: (List<String>) -> Unit): String? {
        val key = liftedKey?.takeIf { dragging } ?: return null
        val from = translationOf(key)
        dragging = false
        val next = order
        // Committing even an order equal to the presented one overrides a commit of the previous drag still in flight.
        if (swapped && next != null && next != pickedOrder) commit(next) else if (next == shown) order = null
        settle(scope, from)
        return key.takeIf { !swapped && travel <= touchSlop }
    }

    /** A cancelled gesture puts the order back as it was when the tile was lifted. */
    fun cancel() {
        if (!dragging) return
        dragging = false
        liftedKey = null
        order = pickedOrder.takeUnless { it == shown }
    }

    /** Whether [key] can move [step] places among [presented]; see [stepTile]. */
    fun canStep(presented: List<String>, key: String, step: Int): Boolean = !dragging && step(presented, key, step) != null

    /** Moves [key] [step] places without a drag, for TalkBack, and commits the result at once. */
    fun stepAndCommit(presented: List<String>, key: String, step: Int, commit: (List<String>) -> Unit): Boolean {
        val next = step(presented, key, step)?.takeIf { !dragging } ?: return false
        order = next
        commit(next)
        return true
    }

    /** Scrolls while the lifted tile is near the grid's top or bottom edge, for as long as the drag lasts. */
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
        // Until the grid lays the last swap out, its cells still describe the order before it.
        if (visible.any { current.getOrNull(it.index) != it.key }) return
        val centre = finger + pickedSize.center.toOffset()
        val target = visible.firstOrNull { it.key != key && it.contains(centre) }?.key as? String ?: return
        val next = moveTile(current, key, target, current.filter(isFixed).toSet())
        if (next == current) return
        // The grid keeps its first visible key in place, so moving that tile would scroll the grid with it.
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

/**
 * Lifts a tile of the grid this modifies on long press and drags it until release; see [CodexGridReorderState].
 * [onCommit] receives a changed order once; [onOpenActions] the key of a tile released where it was lifted.
 */
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

/**
 * Feeds the lifted pointer to [state] until it lifts (true) or goes missing (false). It is consumed in the initial
 * pass, so neither the grid's scrolling nor the tile's click acts on it.
 */
private suspend fun AwaitPointerEventScope.followFinger(id: PointerId, state: CodexGridReorderState, haptics: HapticFeedback): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return false
        val delta = change.positionChangeIgnoreConsumed()
        change.consume()
        if (!change.pressed) return true
        state.dragBy(delta, haptics)
    }
}
