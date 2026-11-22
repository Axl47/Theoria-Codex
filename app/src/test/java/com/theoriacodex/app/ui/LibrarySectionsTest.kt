package com.theoriacodex.app.ui

import com.theoriacodex.app.library.LibrarySection
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySectionsTest {
    @Test fun `last tabs from before Library restore to it and unknown ones to Search`() {
        assertEquals(TopLevelDestination.Search, restoredHomeTab("search"))
        assertEquals(TopLevelDestination.ForYou, restoredHomeTab("for_you"))
        assertEquals(TopLevelDestination.Library, restoredHomeTab("library"))
        listOf("recents", "codex", "settings").forEach { assertEquals(TopLevelDestination.Library, restoredHomeTab(it)) }
        assertEquals(TopLevelDestination.Search, restoredHomeTab("downloads"))
        assertEquals(TopLevelDestination.Search, restoredHomeTab(null))
        assertEquals(LibrarySection.History, legacyTabSections["recents"])
        assertEquals(LibrarySection.Codices, legacyTabSections["codex"])
        assertEquals(null, legacyTabSections["settings"])
    }

    @Test fun `the Series tab shows and restores only while an extension is on the device`() {
        val without = homeTabs(withSeries = false)
        assertEquals(listOf(TopLevelDestination.Search, TopLevelDestination.ForYou, TopLevelDestination.Library), without)
        assertEquals(without + TopLevelDestination.Series, homeTabs(withSeries = true))
        assertEquals(TopLevelDestination.Series, restoredHomeTab("series", homeTabs(withSeries = true)))
        assertEquals(TopLevelDestination.Search, restoredHomeTab("series", without))
    }

    @Test fun `a saved Series tab the app only remembers as missing is kept until the app has looked`() {
        val without = homeTabs(withSeries = false)
        // Remembered "no extension", not looked yet: Search stands in, and resting on it does not replace the saved tab.
        val awaited = checkNotNull(AwaitedSeriesTab.at("series", without, looked = false))
        assertEquals(AwaitedSeriesTab(standIn = "search"), awaited)
        assertEquals(false, awaited.saves("search"))
        // The look finds an extension: Series is taken up, unless the user moved to another tab meanwhile.
        assertEquals("series", awaited.resolved(seriesShows = true, current = "search"))
        assertEquals(null, awaited.resolved(seriesShows = true, current = "library"))
        assertEquals(true, awaited.saves("library"))
        // The look finds none: nothing to move to, and the stand-in is saved from then on.
        assertEquals(null, awaited.resolved(seriesShows = false, current = "search"))
    }

    @Test fun `nothing is awaited when the answer is known or the tab already shows`() {
        val without = homeTabs(withSeries = false)
        // Looked and found none, or a remembered "yes" that the look turned into "no": the fallback is saved as before.
        assertEquals(null, AwaitedSeriesTab.at("series", without, looked = true))
        assertEquals(null, AwaitedSeriesTab.at("series", homeTabs(withSeries = true), looked = false))
        assertEquals(null, AwaitedSeriesTab.at("library", without, looked = false))
    }
}
