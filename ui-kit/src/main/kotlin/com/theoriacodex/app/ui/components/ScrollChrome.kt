package com.theoriacodex.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.theoriacodex.app.ui.LocalTabShown
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
internal class ScrollChromeState {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var active by mutableStateOf(true)
    private val hideableBottoms = mutableStateMapOf<Any, Int>()
    private val focused = mutableStateMapOf<Any, Unit>()
    val hideablePx: Int get() = if (active && focused.isEmpty()) hideableBottoms.values.maxOrNull() ?: 0 else 0
    val shiftPx: Int get() = offsetPx.roundToInt().coerceIn(0, hideablePx)
    var atTop: () -> Boolean = { false }
    var root: LayoutCoordinates? = null
    private var hiding = false
    private var generation = 0

    fun consume(deltaY: Float): Float {
        val range = hideablePx.toFloat()
        if (deltaY == 0f || range == 0f) return 0f
        generation++
        hiding = deltaY < 0f
        val before = offsetPx.coerceAtMost(range)
        offsetPx = (before - deltaY).coerceIn(0f, range)
        return before - offsetPx
    }

    fun preScroll(deltaY: Float): Float = if (deltaY < 0f && atTop()) 0f else consume(deltaY)

    fun settleTarget(): Float {
        val range = hideablePx.toFloat()
        return if (hiding && range > 0f && !atTop()) range else 0f
    }

    fun show() {
        generation++
        hiding = false
        offsetPx = 0f
    }

    suspend fun settle() = animateTo(settleTarget())

    suspend fun animateTo(target: Float) {
        val start = ++generation
        if (target == 0f) hiding = false
        val from = offsetPx.coerceIn(0f, hideablePx.toFloat())
        offsetPx = from
        if (from == target) return
        animate(from, target, animationSpec = tween(TheoriaMotion.SCREEN_MS)) { value, _ ->
            if (generation == start) offsetPx = value
        }
    }

    fun place(key: Any, coordinates: LayoutCoordinates) {
        val parent = root?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        record(key, (parent.localPositionOf(coordinates, Offset.Zero).y + coordinates.size.height).roundToInt())
    }

    fun record(key: Any, bottom: Int) {
        if (hideableBottoms[key] != bottom) hideableBottoms[key] = bottom
    }

    fun focus(key: Any, hasFocus: Boolean) {
        if (hasFocus) {
            focused[key] = Unit
            show()
        } else {
            focused.remove(key)
        }
    }

    fun forget(key: Any) {
        hideableBottoms.remove(key)
        focused.remove(key)
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = Offset(0f, preScroll(available.y))
    }
}

internal val LocalScrollChrome = staticCompositionLocalOf<ScrollChromeState?> { null }

@Composable
fun ScrollChromeBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = remember { ScrollChromeState() }
    val tabShown = LocalTabShown.current
    val shown by remember(tabShown) { derivedStateOf(tabShown) }
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val active = enabled && shown && started && !rememberTouchExplorationEnabled()
    SideEffect {
        if (state.active != active) {
            state.active = active
            state.show()
        }
    }
    val measure = remember(state) { scrollChromeMeasure(state) }
    CompositionLocalProvider(LocalScrollChrome provides state) {
        Layout(
            content = { Box(Modifier.onPlaced { state.root = it }) { content() } },
            modifier = modifier.clipToBounds().nestedScroll(state.connection),
            measurePolicy = measure,
        )
    }
}

private fun scrollChromeMeasure(state: ScrollChromeState) = MeasurePolicy { measurables, constraints ->
    if (!constraints.hasBoundedHeight) {
        val placeable = measurables.single().measure(constraints)
        return@MeasurePolicy layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val shift = state.shiftPx
    val height = constraints.maxHeight + shift
    val placeable = measurables.single().measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth), constraints.maxHeight) {
        placeable.place(0, -shift)
    }
}

@Composable
fun Modifier.hidesOnScroll(): Modifier {
    val chrome = LocalScrollChrome.current ?: return this
    val key = remember { Any() }
    DisposableEffect(chrome, key) { onDispose { chrome.forget(key) } }
    return onGloballyPositioned { chrome.place(key, it) }
        .onFocusChanged { chrome.focus(key, it.hasFocus) }
}

@Composable
internal fun ScrollChromeTopEffect(chrome: ScrollChromeState, scrollable: ScrollableState) {
    DisposableEffect(chrome, scrollable) {
        chrome.atTop = { !scrollable.canScrollBackward }
        onDispose {
            chrome.atTop = { false }
            chrome.show()
        }
    }
    LaunchedEffect(chrome, scrollable) {
        launch { snapshotFlow { !scrollable.canScrollBackward }.collect { top -> if (top) chrome.animateTo(0f) } }
        snapshotFlow { scrollable.isScrollInProgress }.drop(1).collectLatest { scrolling ->
            if (!scrolling) {
                withFrameNanos {}
                chrome.settle()
            }
        }
    }
}
