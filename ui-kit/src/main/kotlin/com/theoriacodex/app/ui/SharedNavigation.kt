package com.theoriacodex.app.ui

import com.theoriacodex.app.ui.components.ScrollChromeTopEffect
import com.theoriacodex.app.ui.components.LocalScrollChrome
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter

enum class TopLevelDestination(val route: String, val label: String) {
    Search("search", "Search"),
    ForYou("for_you", "For You"),
    Library("library", "Library"),
    Series("series", "Series"),
}

class CodexMorphScope(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalCodexMorph = staticCompositionLocalOf<CodexMorphScope?> { null }

@Composable
fun Modifier.codexMorph(codexId: String, morph: CodexMorphScope? = LocalCodexMorph.current): Modifier {
    if (morph == null) return this
    return with(morph.shared) {
        this@codexMorph.sharedBounds(
            sharedContentState = rememberSharedContentState("codex-$codexId"),
            animatedVisibilityScope = morph.visibility,
            boundsTransform = { _, _ -> tween(TheoriaMotion.SCREEN_MS) },
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)),
        )
    }
}

val LocalNetworkOnline = staticCompositionLocalOf<StateFlow<Boolean>> { MutableStateFlow(true) }

val LocalTabReselects = staticCompositionLocalOf<SharedFlow<TopLevelDestination>> { MutableSharedFlow() }

val LocalTabShown = staticCompositionLocalOf<() -> Boolean> { { true } }

@Composable
fun ScrollToTopOnTabReselect(destination: TopLevelDestination, scrollable: ScrollableState, onReselect: () -> Unit = {}) {
    val chrome = LocalScrollChrome.current
    ScrollToTopOnTabReselect(destination) {
        chrome?.show()
        when (scrollable) {
            is LazyListState -> scrollable.animateScrollToItem(0)
            is LazyGridState -> scrollable.animateScrollToItem(0)
            is LazyStaggeredGridState -> scrollable.animateScrollToItem(0)
        }
        onReselect()
    }
    if (chrome != null) ScrollChromeTopEffect(chrome, scrollable)
}

@Composable
fun ScrollToTopOnTabReselect(destination: TopLevelDestination, scrollToTop: suspend () -> Unit) {
    val reselects = LocalTabReselects.current
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    LaunchedEffect(reselects, destination) {
        reselects.filter { it == destination }.collect { currentScrollToTop() }
    }
}
