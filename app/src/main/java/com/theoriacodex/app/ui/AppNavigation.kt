package com.theoriacodex.app.ui

import com.theoriacodex.domain.series.SeriesId
import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.ui.navigation.TriageMode
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.app.ui.trails.isTrailLevel
import com.theoriacodex.app.ui.trails.viewerForkFadeIn
import com.theoriacodex.app.ui.trails.viewerForkFadeOut
import com.theoriacodex.app.series.ExtensionsSubpage
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

    const val Extensions = "extensions?page={page}"

    fun extensions(page: ExtensionsSubpage? = null): String = "extensions" + page?.let { "?page=${it.name}" }.orEmpty()

    const val Series = "series?source={source}&address={address}&entry={entry}"

    fun series(id: SeriesId, entry: String? = null): String =
        "series?source=${id.source.id}&address=${Uri.encode(id.address)}" + entry?.let { "&entry=${Uri.encode(it)}" }.orEmpty()

    const val Triage = "triage/{source}?feedKey={feedKey}&removes={removes}&alsoIn={alsoIn}"

    fun codexDetail(codexId: String): String {
        return "codex/detail/$codexId"
    }

    fun triage(request: TriageRequest): String {
        val removal = request.mode as? TriageMode.RemoveFromCodex
        val alsoIn = removal?.alsoIn.orEmpty().joinToString(TRIAGE_ID_SEPARATOR)
        return "triage/${request.source.name}?feedKey=${Uri.encode(request.feedKey)}&removes=${removal != null}&alsoIn=${Uri.encode(alsoIn)}"
    }

    const val TRIAGE_ID_SEPARATOR = ","
}

@Composable
internal fun topLevelPagerFling(state: PagerState): TargetedFlingBehavior =
    PagerDefaults.flingBehavior(state = state, snapPositionalThreshold = TheoriaMotion.PAGE_COMMIT_FRACTION)

internal fun tabShown(tab: TopLevelDestination, targetPage: Int, settledPage: Int): Boolean =
    tab.ordinal.let { it == targetPage || it == settledPage }

internal enum class TabTap { Reselect, LetFinish, Move }

internal fun tabTap(tab: TopLevelDestination, targetPage: Int, settledPage: Int, scrolling: Boolean): TabTap = when {
    scrolling -> if (tab.ordinal == targetPage) TabTap.LetFinish else TabTap.Move
    tab.ordinal == settledPage -> TabTap.Reselect
    else -> TabTap.Move
}

internal fun tabSwipeEnabled(settledPage: Int, forYouGrid: Boolean): Boolean =
    settledPage != TopLevelDestination.ForYou.ordinal || forYouGrid

private val screenSlide = tween<IntOffset>(TheoriaMotion.SCREEN_MS)

private fun pushEnterTransition(): EnterTransition = slideInHorizontally(screenSlide) { it }
private fun pushExitTransition(): ExitTransition = slideOutHorizontally(screenSlide) { -it }
private fun popEnterTransition(): EnterTransition = slideInHorizontally(screenSlide) { -it }
private fun popExitTransition(): ExitTransition = slideOutHorizontally(screenSlide) { it }

private val screenFade = tween<Float>(TheoriaMotion.SCREEN_MS)

private fun codexEnterTransition(): EnterTransition = fadeIn(screenFade)
private fun codexExitTransition(): ExitTransition = fadeOut(screenFade)
private fun codexUnderneathExit(): ExitTransition = ExitTransition.None
private fun codexUnderneathEnter(): EnterTransition = EnterTransition.None

private typealias ScreenTransitionScope = AnimatedContentTransitionScope<NavBackStackEntry>

private val ScreenTransitionScope.betweenViewers: Boolean
    get() = initialState.destination.route == AppRoute.Viewer && targetState.destination.route == AppRoute.Viewer

private fun ScreenTransitionScope.screenEnter(): EnterTransition = when {
    betweenViewers -> viewerForkFadeIn()
    targetState.destination.route == AppRoute.Viewer -> EnterTransition.None
    targetState.destination.route == AppRoute.CodexDetail -> codexEnterTransition()
    else -> pushEnterTransition()
}

private fun ScreenTransitionScope.screenExit(): ExitTransition = when {
    betweenViewers -> viewerForkFadeOut()
    targetState.destination.route == AppRoute.Viewer -> ExitTransition.None
    targetState.destination.route == AppRoute.CodexDetail -> codexUnderneathExit()
    else -> pushExitTransition()
}

private fun ScreenTransitionScope.screenPopEnter(): EnterTransition = when {
    betweenViewers -> viewerForkFadeIn()
    initialState.destination.route == AppRoute.Viewer -> EnterTransition.None
    initialState.destination.route == AppRoute.CodexDetail -> codexUnderneathEnter()
    else -> popEnterTransition()
}

private fun ScreenTransitionScope.screenPopExit(): ExitTransition = when {
    initialState.destination.route == AppRoute.Viewer ->
        if (betweenViewers || initialState.isTrailLevel()) viewerForkFadeOut() else ExitTransition.None
    initialState.destination.route == AppRoute.CodexDetail -> codexExitTransition()
    else -> popExitTransition()
}

@Composable
internal fun TheoriaScreenHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { screenEnter() },
        exitTransition = { screenExit() },
        popEnterTransition = { screenPopEnter() },
        popExitTransition = { screenPopExit() },
        predictivePopEnterTransition = { screenPopEnter() },
        predictivePopExitTransition = { screenPopExit() },
        builder = builder,
    )
}

internal fun NavGraphBuilder.opaqueScreen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    reachesBottomEdge: Boolean = false,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        val inset = if (reachesBottomEdge) Modifier else Modifier.padding(screenBottomInsets.asPaddingValues())
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(inset), propagateMinConstraints = true) {
            this@composable.content(entry)
        }
    }
}

@Composable
internal fun RecordCodexEntryEffect(entry: NavBackStackEntry, codexId: String, statistics: StatisticsRepository) {
    LaunchedEffect(entry, codexId, statistics) {
        runCatchingPreservingCancellation { statistics.recordCodexEntry(codexId) }
    }
}

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
