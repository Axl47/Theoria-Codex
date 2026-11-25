package com.theoriacodex.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Velocity
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
class ScrollChromeState {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var active by mutableStateOf(true)
        internal set
    private val hideableBottoms = mutableStateMapOf<Any, Int>()
    val hideablePx: Int get() = if (active) hideableBottoms.values.maxOrNull() ?: 0 else 0
    internal var atTop: () -> Boolean = { false }
    internal var root: LayoutCoordinates? = null
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

    fun settleTarget(): Float {
        val range = hideablePx.toFloat()
        return if (hiding && range > 0f && !atTop()) range else 0f
    }

    fun show() {
        generation++
        hiding = false
        offsetPx = 0f
    }

    internal suspend fun animateTo(target: Float) {
        val start = ++generation
        if (offsetPx == target) return
        animate(offsetPx, target, animationSpec = tween(TheoriaMotion.SCREEN_MS)) { value, _ ->
            if (generation == start) offsetPx = value
        }
    }

    internal fun place(key: Any, coordinates: LayoutCoordinates) {
        val parent = root?.takeIf { it.isAttached } ?: return
        if (!coordinates.isAttached) return
        record(key, (parent.localPositionOf(coordinates, Offset.Zero).y + coordinates.size.height).roundToInt())
    }

    internal fun record(key: Any, bottom: Int) {
        if (hideableBottoms[key] != bottom) hideableBottoms[key] = bottom
    }

    internal fun forget(key: Any) {
        hideableBottoms.remove(key)
    }

    internal fun connection(scope: CoroutineScope) = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = Offset(0f, consume(available.y))

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            scope.launch { animateTo(settleTarget()) }
            return Velocity.Zero
        }
    }
}

val LocalScrollChrome = staticCompositionLocalOf<ScrollChromeState?> { null }

@Composable
fun rememberScrollChromeState(): ScrollChromeState = remember { ScrollChromeState() }

@Composable
fun ScrollChromeBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: ScrollChromeState = rememberScrollChromeState(),
    content: @Composable () -> Unit,
) {
    val active = enabled && !rememberTouchExplorationEnabled()
    SideEffect {
        if (state.active != active) {
            state.active = active
            state.show()
        }
    }
    val scope = rememberCoroutineScope()
    val connection = remember(state, scope) { state.connection(scope) }
    CompositionLocalProvider(LocalScrollChrome provides state) {
        Layout(
            content = { Box(Modifier.onPlaced { state.root = it }) { content() } },
            modifier = modifier.clipToBounds().nestedScroll(connection),
        ) { measurables, constraints ->
            val extra = if (constraints.hasBoundedHeight) state.hideablePx else 0
            val height = constraints.maxHeight + extra
            val placeable = measurables.single().measure(
                if (constraints.hasBoundedHeight) constraints.copy(minHeight = height, maxHeight = height) else constraints,
            )
            val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val visibleHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
            layout(width, visibleHeight) {
                placeable.placeWithLayer(0, 0) { translationY = -state.offsetPx.coerceAtMost(state.hideablePx.toFloat()) }
            }
        }
    }
}

@Composable
fun Modifier.hidesOnScroll(): Modifier {
    val chrome = LocalScrollChrome.current ?: return this
    val key = remember { Any() }
    DisposableEffect(chrome, key) { onDispose { chrome.forget(key) } }
    return onGloballyPositioned { chrome.place(key, it) }
        .onFocusChanged { if (it.hasFocus) chrome.show() }
}

@Composable
fun ScrollChromeTopEffect(scrollable: ScrollableState) {
    val chrome = LocalScrollChrome.current ?: return
    SideEffect { chrome.atTop = { !scrollable.canScrollBackward } }
    LaunchedEffect(chrome, scrollable) {
        snapshotFlow { !scrollable.canScrollBackward }.collect { top -> if (top) chrome.animateTo(0f) }
    }
}
