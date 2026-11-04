package com.theoriacodex.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.statistics.StatisticsUsageCategory
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter

enum class TopLevelDestination(val route: String, val label: String) {
    Search("search", "Search"),
    Recents("recents", "Recents"),
    ForYou("for_you", "For You"),
    Codex("codex", "Codex"),
    Settings("settings", "Settings"),
}

internal object AppRoute {
    const val Home = "home"
    const val CreatorProfile = "creator-profile"
    const val Viewer = "viewer"
    const val CodexDetail = "codex/detail/{codexId}"

    fun codexDetail(codexId: String): String {
        return "codex/detail/$codexId"
    }
}

/** Slow tab swipes commit as readily as Viewer swipes; the library default needs half the screen. */
@Composable
internal fun topLevelPagerFling(state: PagerState): TargetedFlingBehavior =
    PagerDefaults.flingBehavior(state = state, snapPositionalThreshold = TheoriaMotion.PAGE_COMMIT_FRACTION)

private val screenSlide = tween<IntOffset>(TheoriaMotion.SCREEN_MS)

/** Pushed screens enter from the right and push the previous one left; Back plays the exact reverse. */
internal fun pushEnterTransition(): EnterTransition = slideInHorizontally(screenSlide) { it }
internal fun pushExitTransition(): ExitTransition = slideOutHorizontally(screenSlide) { -it }
internal fun popEnterTransition(): EnterTransition = slideInHorizontally(screenSlide) { -it }
internal fun popExitTransition(): ExitTransition = slideOutHorizontally(screenSlide) { it }

private val screenFade = tween<Float>(TheoriaMotion.SCREEN_MS)

/**
 * A Codex opens by growing out of its tile, so it only fades above the screen beneath it. That
 * screen stays opaque throughout: fading both would let whatever is behind them show through.
 */
internal fun codexEnterTransition(): EnterTransition = fadeIn(screenFade)
internal fun codexExitTransition(): ExitTransition = fadeOut(screenFade)
internal fun codexUnderneathExit(): ExitTransition = ExitTransition.None
internal fun codexUnderneathEnter(): EnterTransition = EnterTransition.None

/** Lets a Codex tile and its detail screen morph into each other across navigation. */
internal class CodexMorphScope(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

internal val LocalCodexMorph = staticCompositionLocalOf<CodexMorphScope?> { null }

/** Marks a Codex tile or detail screen; a Codex opened from anywhere else simply fades in. */
@Composable
internal fun Modifier.codexMorph(codexId: String, morph: CodexMorphScope? = LocalCodexMorph.current): Modifier {
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
internal val LocalTabReselects = staticCompositionLocalOf<SharedFlow<TopLevelDestination>> { MutableSharedFlow() }

/** Returns [destination]'s content to its top when its tab is tapped again. */
@Composable
internal fun ScrollToTopOnTabReselect(destination: TopLevelDestination, scrollToTop: suspend () -> Unit) {
    val reselects = LocalTabReselects.current
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    LaunchedEffect(reselects, destination) {
        reselects.filter { it == destination }.collect { currentScrollToTop() }
    }
}

/** Counts one Codex entry per back stack entry; a statistics failure never affects navigation. */
@Composable
internal fun RecordCodexEntryEffect(entry: NavBackStackEntry, codexId: String, statistics: StatisticsRepository) {
    LaunchedEffect(entry, codexId, statistics) {
        runCatchingPreservingCancellation { statistics.recordCodexEntry(codexId) }
    }
}

internal fun statisticsUsageCategory(
    currentRoute: String?,
    homeTabRoute: String,
): StatisticsUsageCategory? = when (currentRoute) {
    AppRoute.Viewer -> StatisticsUsageCategory.WATCHING
    AppRoute.CreatorProfile -> StatisticsUsageCategory.BROWSING
    AppRoute.CodexDetail -> StatisticsUsageCategory.CODEX
    AppRoute.Home -> when (homeTabRoute) {
        TopLevelDestination.Search.route,
        TopLevelDestination.Recents.route,
        TopLevelDestination.ForYou.route,
        -> StatisticsUsageCategory.BROWSING
        TopLevelDestination.Codex.route -> StatisticsUsageCategory.CODEX
        else -> null
    }
    else -> null
}
