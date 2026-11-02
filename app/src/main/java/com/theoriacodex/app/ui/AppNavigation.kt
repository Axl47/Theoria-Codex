package com.theoriacodex.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavBackStackEntry
import com.theoriacodex.app.statistics.StatisticsUsageCategory
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation

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

/** A Codex slides back in only when another Codex pops off it, as with saved sub-Codices. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.codexDetailPopEnterTransition(): EnterTransition =
    if (initialState.destination.route == AppRoute.CodexDetail) {
        slideInHorizontally(tween(300)) { -it }
    } else {
        EnterTransition.None
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
