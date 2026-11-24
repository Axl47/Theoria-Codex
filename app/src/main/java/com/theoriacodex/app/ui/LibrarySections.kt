package com.theoriacodex.app.ui

import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.library.LibrarySection

internal val legacyTabSections = mapOf("recents" to LibrarySection.History, "codex" to LibrarySection.Codices, "settings" to null)

internal fun restoredHomeTab(stored: String?): TopLevelDestination =
    TopLevelDestination.entries.firstOrNull { it.route == stored }
        ?: TopLevelDestination.Library.takeIf { stored in legacyTabSections }
        ?: TopLevelDestination.Search

internal suspend fun DataDependencies.restoreHomeTab(): TopLevelDestination {
    val stored = uiRestoreRepository.getLastTab()
    librarySections.restore(legacyTabSections[stored])
    return restoredHomeTab(stored)
}

internal fun TheoriaShell.goToLibrary(section: LibrarySection) {
    data.librarySections.select(section)
    navigation.goToTab(TopLevelDestination.Library)
}
