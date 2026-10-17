package com.theoriacodex.app.ui

import com.theoriacodex.app.statistics.StatisticsUsageCategory

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
