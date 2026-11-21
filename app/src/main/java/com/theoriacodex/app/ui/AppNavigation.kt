package com.theoriacodex.app.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.statistics.StatisticsUsageCategory
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation

internal object AppRoute {
    const val Home = "home"
    const val CreatorProfile = "creator-profile"
    const val Viewer = "viewer"
    const val CodexDetail = "codex/detail/{codexId}"
    const val Settings = "settings-screen"

    /** The triage deck over one feed, named by a [TriageRequest]. */
    const val Triage = "triage/{source}?feedKey={feedKey}"

    fun codexDetail(codexId: String): String {
        return "codex/detail/$codexId"
    }

    fun triage(request: TriageRequest): String = "triage/${request.source.name}?feedKey=${Uri.encode(request.feedKey)}"
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

/** Counts one Codex entry per back stack entry; a statistics failure never affects navigation. */
@Composable
internal fun RecordCodexEntryEffect(entry: NavBackStackEntry, codexId: String, statistics: StatisticsRepository) {
    LaunchedEffect(entry, codexId, statistics) {
        runCatchingPreservingCancellation { statistics.recordCodexEntry(codexId) }
    }
}

/**
 * What time on a screen counts as. Library keeps the meaning of the tabs it replaced: History browses
 * like Recents did, Codices and Saved (once a Codex screen) count as Codex. Settings counts as nothing.
 */
internal fun statisticsUsageCategory(
    currentRoute: String?,
    homeTabRoute: String,
    librarySection: LibrarySection,
): StatisticsUsageCategory? = when (currentRoute) {
    AppRoute.Viewer -> StatisticsUsageCategory.WATCHING
    AppRoute.CreatorProfile -> StatisticsUsageCategory.BROWSING
    AppRoute.CodexDetail -> StatisticsUsageCategory.CODEX
    AppRoute.Home -> when (homeTabRoute) {
        TopLevelDestination.Search.route,
        TopLevelDestination.ForYou.route,
        -> StatisticsUsageCategory.BROWSING
        TopLevelDestination.Library.route ->
            if (librarySection == LibrarySection.History) StatisticsUsageCategory.BROWSING else StatisticsUsageCategory.CODEX
        else -> null
    }
    else -> null
}
