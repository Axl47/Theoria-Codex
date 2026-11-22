package com.theoriacodex.app.ui

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

/**
 * The bottom bar's tabs, in pager order. [route] is what the last-tab preference stores. [Series] stays last because
 * only a build with extensions shows it ([homeTabs]), which keeps every other tab's page index the same either way.
 */
enum class TopLevelDestination(val route: String, val label: String) {
    Search("search", "Search"),
    ForYou("for_you", "For You"),
    Library("library", "Library"),
    Series("series", "Series"),
}

/** The tabs a build shows: all of them [withSeries], otherwise all but [TopLevelDestination.Series]. */
fun homeTabs(withSeries: Boolean): List<TopLevelDestination> =
    TopLevelDestination.entries.filter { withSeries || it != TopLevelDestination.Series }

/** Lets a Codex tile and its detail screen morph into each other across navigation. */
class CodexMorphScope(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalCodexMorph = staticCompositionLocalOf<CodexMorphScope?> { null }

/** Marks a Codex tile or detail screen; a Codex opened from anywhere else simply fades in. */
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

/** Tapping the tab that is already showing; each tab's scrolling content listens for its own. */
/** Ambient connectivity for failure wording and for the feed owners that retry on reconnect. */
val LocalNetworkOnline = staticCompositionLocalOf<StateFlow<Boolean>> { MutableStateFlow(true) }

val LocalTabReselects = staticCompositionLocalOf<SharedFlow<TopLevelDestination>> { MutableSharedFlow() }

/** Returns [destination]'s content to its top when its tab is tapped again. */
@Composable
fun ScrollToTopOnTabReselect(destination: TopLevelDestination, scrollToTop: suspend () -> Unit) {
    val reselects = LocalTabReselects.current
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    LaunchedEffect(reselects, destination) {
        reselects.filter { it == destination }.collect { currentScrollToTop() }
    }
}
