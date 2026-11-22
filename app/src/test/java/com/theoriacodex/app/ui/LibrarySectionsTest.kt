package com.theoriacodex.app.ui

import com.theoriacodex.app.library.LibrarySection
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySectionsTest {
    @Test fun `a saved tab restores, tabs from before Library restore to it and unknown ones to Search`() {
        assertEquals(TopLevelDestination.Search, restoredHomeTab("search"))
        assertEquals(TopLevelDestination.ForYou, restoredHomeTab("for_you"))
        assertEquals(TopLevelDestination.Library, restoredHomeTab("library"))
        assertEquals(TopLevelDestination.Series, restoredHomeTab("series"))
        listOf("recents", "codex", "settings").forEach { assertEquals(TopLevelDestination.Library, restoredHomeTab(it)) }
        assertEquals(TopLevelDestination.Search, restoredHomeTab("downloads"))
        assertEquals(TopLevelDestination.Search, restoredHomeTab(null))
        assertEquals(LibrarySection.History, legacyTabSections["recents"])
        assertEquals(LibrarySection.Codices, legacyTabSections["codex"])
        assertEquals(null, legacyTabSections["settings"])
    }
}
