package com.theoriacodex.app.ui

import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.library.LibrarySection

/** Tab routes stored before Library replaced them, and the section each became. Settings keeps Library's own. */
internal val legacyTabSections = mapOf("recents" to LibrarySection.History, "codex" to LibrarySection.Codices, "settings" to null)

/** The tab a stored last-tab route restores to: retired tabs open Library, and anything unknown opens Search. */
internal fun restoredHomeTab(stored: String?): TopLevelDestination =
    TopLevelDestination.entries.firstOrNull { it.route == stored }
        ?: TopLevelDestination.Library.takeIf { stored in legacyTabSections }
        ?: TopLevelDestination.Search

/** Restores the last tab and Library's section at startup. */
internal suspend fun DataDependencies.restoreHomeTab(): TopLevelDestination {
    val stored = uiRestoreRepository.getLastTab()
    librarySections.restore(legacyTabSections[stored])
    return restoredHomeTab(stored)
}

/** Selects Library at [section] from anywhere, returning to Home first when another screen is on top. */
internal fun TheoriaShell.goToLibrary(section: LibrarySection) {
    data.librarySections.select(section)
    navigation.goToTab(TopLevelDestination.Library)
}
