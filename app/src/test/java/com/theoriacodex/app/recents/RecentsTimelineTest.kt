package com.theoriacodex.app.recents

import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.begin
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

    private fun post(at: Long, id: String = "1") = RecentPostEntry(testPost(sourcePostId = id), at, ViewerStreamSource.SEARCH, null)

    private fun shelfFor(entry: RecentPostEntry) =
        RelatedPostsUiState.Idle.begin(entry.post, anchorCanonicalIndex = 0, generation = 1).request

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
                RecentsTimelineItem.Day(recentsDay(now, ZoneOffset.UTC), "Today"),
                RecentsTimelineItem.Posts(listOf(first, second)),
                RecentsTimelineItem.Day(recentsDay(now - day, ZoneOffset.UTC), "Yesterday"),
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

    @Test fun `same month and day in different years have distinct identities`() {
        val dates = listOf("2025-01-01T12:00:00Z", "2024-06-01T12:00:00Z", "2024-01-01T12:00:00Z")
        val posts = dates.map { post(java.time.Instant.parse(it).toEpochMilli()) }
        val headings = recentsTimeline(posts.map(RecentActivityEntry::Watched), now, ZoneOffset.UTC)
            .filterIsInstance<RecentsTimelineItem.Day>()
        assertEquals(3, headings.map { it.date }.distinct().size)
        assertEquals(3, recentsGridRows(posts, now, ZoneOffset.UTC).filterIsInstance<RecentsGridRow.Day>().size)
    }

    @Test fun `the shelf follows its seed when a newly viewed post pushes it down`() {
        val seed = post(now - 2, "seed")
        val other = post(now - day, "other")
        val request = shelfFor(seed)
        val viewedLater = listOf(post(now - 1, "new"), seed, other)

        val rows = recentsGridRows(viewedLater, now, ZoneOffset.UTC).withRelatedShelf(request, viewedLater)
        assertEquals(RecentsGridRow.Post(1, seed), rows[rows.indexOf(RecentsGridRow.RelatedShelf) - 1])

        val timeline = recentsTimeline(viewedLater.map(RecentActivityEntry::Watched), now, ZoneOffset.UTC)
            .withRelatedShelf(request)
        val shelf = timeline.indexOf(RecentsTimelineItem.RelatedShelf)
        assertEquals(RecentsTimelineItem.Posts(listOf(viewedLater[0], seed)), timeline[shelf - 1])
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
