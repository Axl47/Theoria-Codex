package com.theoriacodex.app.recents

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentsTimelineTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_767_268_800_000L // 2026-01-01T12:00Z

    private fun post(at: Long) = RecentPostEntry(testPost(), at, ViewerStreamSource.SEARCH, null)

    @Test fun `day labels read today yesterday then the date`() {
        assertEquals("Today", recentsDayLabel(now - 1_000, now, ZoneOffset.UTC))
        assertEquals("Yesterday", recentsDayLabel(now - day, now, ZoneOffset.UTC))
        assertEquals("Dec 30", recentsDayLabel(now - 2 * day, now, ZoneOffset.UTC))
    }

    @Test fun `consecutive posts share a shelf within each day`() {
        val first = post(now - 1)
        val second = post(now - 2)
        val older = post(now - day)
        val timeline = recentsTimeline(
            listOf(first, second, older).map(RecentActivityEntry::Watched), now, ZoneOffset.UTC,
        )
        assertEquals(
            listOf(
                RecentsTimelineItem.Day("Today"),
                RecentsTimelineItem.Posts(listOf(first, second)),
                RecentsTimelineItem.Day("Yesterday"),
                RecentsTimelineItem.Posts(listOf(older)),
            ),
            timeline,
        )
    }

    @Test fun `grid rows keep each post's index in the visible list`() {
        val rows = recentsGridRows(listOf(post(now - 1), post(now - day)), now, ZoneOffset.UTC)
        assertEquals(listOf(0, 1), rows.filterIsInstance<RecentsGridRow.Post>().map { it.index })
        assertEquals(4, rows.size)
    }

    @Test fun `search title shows alternatives and exclusions`() {
        val query = emptySearchQuery().copy(
            includeTermGroups = listOf(
                SearchTermGroup.single(SearchTerm("mika")),
                SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
            ),
            excludeTerms = listOf(SearchTerm("sketch")),
        )
        assertEquals("mika, cat or dog, -sketch", recentSearchRowTitle(query))
    }
}
