package com.theoriacodex.app.search

import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchDateRangeTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    @Test fun `each preset round trips while it still ends now`() {
        DateRangePreset.entries.forEach { preset ->
            assertEquals(preset, presetFor(preset.rangeEndingAt(now), now))
        }
    }

    @Test fun `a preset chosen days ago reads as custom`() {
        assertNull(presetFor(DateRangePreset.LAST_7_DAYS.rangeEndingAt(now - 3 * day), now))
    }

    @Test fun `custom range covers its whole last day and labels in UTC`() {
        val from = 1_767_225_600_000L // 2026-01-01T00:00Z
        val range = customDateRange(from, from + 7 * day)
        assertEquals(from + 8 * day - 1, range.toEpochMs)
        assertEquals("Jan 1, 2026 – Jan 8, 2026", dateRangeLabel(range, now))
    }

    @Test fun `local date filter keeps posts in range and hides unknown dates`() {
        val inRange = post("in", now - day)
        val filters = SearchVisibilityFilters(dateRange = DateRange(now - 2 * day, now))
        val visible = filterSearchResults(
            listOf(inRange, post("old", now - 5 * day), post("unknown", null)),
            filters, emptySet(), emptySet(),
        )
        assertEquals(listOf(inRange), visible)
    }

    private fun post(id: String, created: Long?) = Post(
        id = PostId(SourceKey.GELBOORU, id),
        preview = ImageRef(null, null, null),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = created,
    )
}
