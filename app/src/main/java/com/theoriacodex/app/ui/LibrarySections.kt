package com.theoriacodex.app.ui

import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.library.LibrarySection

/** Tab routes stored before Library replaced them, and the section each became. Settings keeps Library's own. */
internal val legacyTabSections = mapOf("recents" to LibrarySection.History, "codex" to LibrarySection.Codices, "settings" to null)

/**
 * The tab a stored last-tab route restores to, among the [tabs] showing: retired tabs open Library, and anything
 * unknown or hidden, such as Series once the last extension is gone, opens Search.
 */
internal fun restoredHomeTab(stored: String?, tabs: List<TopLevelDestination> = TopLevelDestination.entries): TopLevelDestination =
    tabs.firstOrNull { it.route == stored }
        ?: TopLevelDestination.Library.takeIf { stored in legacyTabSections }
        ?: TopLevelDestination.Search

/**
 * A saved Series tab that startup could not show. At startup the app only remembers whether an extension was on the
 * device; until it has looked, a missing Series tab may still appear. [standIn] is the tab shown meanwhile.
 */
internal data class AwaitedSeriesTab(val standIn: String) {
    /** Whether the pager resting on [route] is saved as the last tab. The stand-in is not: it would replace the saved Series tab. */
    fun saves(route: String): Boolean = route != standIn

    /** The tab to move to once the app has looked: Series when it shows and the user is still on the stand-in. */
    fun resolved(seriesShows: Boolean, current: String): String? = TopLevelDestination.Series.route.takeIf { seriesShows && current == standIn }

    companion object {
        /** Null unless Series is the saved tab, is not among [tabs], and the app has not [looked] for extensions yet. */
        fun at(stored: String?, tabs: List<TopLevelDestination>, looked: Boolean): AwaitedSeriesTab? =
            if (looked || stored != TopLevelDestination.Series.route || TopLevelDestination.Series in tabs) null
            else AwaitedSeriesTab(restoredHomeTab(stored, tabs).route)
    }
}

internal data class RestoredHomeTab(val route: String, val awaitedSeries: AwaitedSeriesTab?)

/**
 * Restores the last tab, if it is among [tabs], and Library's section at startup. [extensionsLooked] says whether the
 * app has looked for extensions since it started; see [AwaitedSeriesTab].
 */
internal suspend fun DataDependencies.restoreHomeTab(tabs: List<TopLevelDestination>, extensionsLooked: Boolean): RestoredHomeTab {
    val stored = uiRestoreRepository.getLastTab()
    librarySections.restore(legacyTabSections[stored])
    return RestoredHomeTab(restoredHomeTab(stored, tabs).route, AwaitedSeriesTab.at(stored, tabs, extensionsLooked))
}

/** Selects Library at [section] from anywhere, returning to Home first when another screen is on top. */
internal fun TheoriaShell.goToLibrary(section: LibrarySection) {
    data.librarySections.select(section)
    navigation.goToTab(TopLevelDestination.Library)
}
