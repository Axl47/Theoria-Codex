package com.theoriacodex.app.search

import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class SearchDateRangeTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    @Test fun `a preset stays that preset and covers the days before each run (ADR 0084)`() {
        DateRangePreset.entries.forEach { preset -> assertEquals(preset, presetFor(preset.range)) }
        val week = checkNotNull(DateRangePreset.LAST_7_DAYS.range)
        val later = now + 30 * day
        val visible = filterSearchResults(
            listOf(post("chosen-day", now), post("yesterday", later - day)),
            SearchVisibilityFilters(dateRange = week), emptySet(), emptySet(), nowEpochMs = later,
        )
        assertEquals(listOf("yesterday"), visible.map { it.id.sourcePostId })
    }

    @Test fun `picked dates stay custom (ADR 0084)`() {
        assertNull(presetFor(DateRange.Between(now - 7 * day, now)))
    }

    @Test fun `custom range covers its whole last day and labels in UTC (ADR 0084)`() {
        val from = 1_767_225_600_000L
        val range = customDateRange(from, from + 7 * day)
        assertEquals(from + 8 * day - 1, range.toEpochMs)
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            assertEquals("Jan 1, 2026 – Jan 8, 2026", dateRangeLabel(range))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun `local date filter keeps posts in range and hides unknown dates (ADR 0084)`() {
        val inRange = post("in", now - day)
        val filters = SearchVisibilityFilters(dateRange = DateRange.Between(now - 2 * day, now))
        val visible = filterSearchResults(
            listOf(post("old", now - 5 * day), inRange, post("future", now + day), post("unknown", null)),
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
