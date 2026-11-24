package com.theoriacodex.app.ui

import com.theoriacodex.domain.series.SeriesId
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
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.statistics.StatisticsUsageCategory
import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation

internal object AppRoute {
    const val Home = "home"
    const val CreatorProfile = "creator-profile"
    const val Viewer = "viewer"
    const val CodexDetail = "codex/detail/{codexId}"
    const val Settings = "settings-screen"

    /** Every extension on the device: which are on, their languages and their settings. */
    const val Extensions = "extensions"

    /** One series' page, named by its [SeriesId]; with an entry address, the page opens that entry in the Viewer once. */
    const val Series = "series?source={source}&address={address}&entry={entry}"

    fun series(id: SeriesId, entry: String? = null): String =
        "series?source=${id.source.id}&address=${Uri.encode(id.address)}" + entry?.let { "&entry=${Uri.encode(it)}" }.orEmpty()

    /** The triage deck over one feed, named by a [TriageRequest]; [triageArguments] reads it back. */
    const val Triage = "triage/{source}?feedKey={feedKey}&removes={removes}&alsoIn={alsoIn}"

    fun codexDetail(codexId: String): String {
        return "codex/detail/$codexId"
    }

    fun triage(request: TriageRequest): String {
        val removal = request.mode as? TriageMode.RemoveFromCodex
        val alsoIn = removal?.alsoIn.orEmpty().joinToString(TRIAGE_ID_SEPARATOR)
        return "triage/${request.source.name}?feedKey=${Uri.encode(request.feedKey)}&removes=${removal != null}&alsoIn=${Uri.encode(alsoIn)}"
    }

    /** Joins the Codex ids of [TriageMode.RemoveFromCodex.alsoIn]; Codex ids never contain it. */
    const val TRIAGE_ID_SEPARATOR = ","
}

/** Slow tab swipes commit as readily as Viewer swipes; the library default needs half the screen. */
@Composable
internal fun topLevelPagerFling(state: PagerState): TargetedFlingBehavior =
    PagerDefaults.flingBehavior(state = state, snapPositionalThreshold = TheoriaMotion.PAGE_COMMIT_FRACTION)

/**
 * Whether [tab] is where the tabs rest or are heading. A bar tap from Search to Library scrolls across For You
 * without ever targeting it; a swipe toward a tab targets it as soon as it is under way.
 */
internal fun tabShown(tab: TopLevelDestination, targetPage: Int, settledPage: Int): Boolean =
    tab.ordinal.let { it == targetPage || it == settledPage }

/** What a bar tap on a tab does while Home is on top; [tabTap] decides. */
internal enum class TabTap { Reselect, LetFinish, Move }

/**
 * A tap on the tab the pager rests on reselects it, a tap on the tab a scroll is already heading to lets that scroll
 * finish, and any other tap moves there. Never decided from the nearest page: a scroll to another tab passes over the
 * tabs between, and a tap on one of those is a tab change.
 */
internal fun tabTap(tab: TopLevelDestination, targetPage: Int, settledPage: Int, scrolling: Boolean): TabTap = when {
    scrolling -> if (tab.ordinal == targetPage) TabTap.LetFinish else TabTap.Move
    tab.ordinal == settledPage -> TabTap.Reselect
    else -> TabTap.Move
}

/**
 * Whether a sideways swipe changes tab from the tab the pager has settled on. Not while For You shows its deck, where
 * a sideways drag decides a card; there the tabs change from the bottom bar only. For You's grid swipes like the other
 * tabs, because a pager that takes no drags leaves them to end as taps on whatever they started on.
 */
internal fun tabSwipeEnabled(settledPage: Int, forYouGrid: Boolean): Boolean =
    settledPage != TopLevelDestination.ForYou.ordinal || forYouGrid

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
 * like Recents did, Codices and Saved (once a Codex screen) count as Codex. Settings counts as nothing. In the Viewer,
 * [viewerSession]'s series tells reading a series' chapters or watching its episodes from watching posts.
 */
internal fun statisticsUsageCategory(
    currentRoute: String?,
    homeTabRoute: String,
    librarySection: LibrarySection,
    viewerSession: ViewerSession? = null,
): StatisticsUsageCategory? = when (currentRoute) {
    AppRoute.Viewer -> when (viewerSession?.series) {
        is ViewerSeries.Reading -> StatisticsUsageCategory.READING
        is ViewerSeries.Watching -> StatisticsUsageCategory.ANIME
        null -> StatisticsUsageCategory.WATCHING
    }
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
