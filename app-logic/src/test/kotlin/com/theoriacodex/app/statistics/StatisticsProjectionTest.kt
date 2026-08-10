package com.theoriacodex.app.statistics

import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.data.repository.UsageDurationDelta
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsProjectionTest {
    @Test
    fun `projection combines live usage and deduplicates saved posts across Codices`() {
        val shared = post(SourceKey.PIXIV, "1", listOf("Blue Hair", "portrait"))
        val other = post(SourceKey.GELBOORU, "2", listOf("blue_hair"))
        val first = codex("first", "First")
        val second = codex("second", "Second")

        val summary = StatisticsProjection.build(
            lifetime = LifetimeStatistics(
                totalForegroundMs = 1_000L,
                browsingMs = 500L,
                codexEntryCounts = mapOf("first" to 3L),
            ),
            liveUsage = UsageDurationDelta(totalMs = 2_000L, watchingMs = 2_000L),
            codices = listOf(first, second),
            postsByCodex = mapOf(
                first.codexId to listOf(shared, other),
                second.codexId to listOf(shared),
            ),
        )

        assertEquals(3_000L, summary.totalForegroundMs)
        assertEquals(500L, summary.browsingMs)
        assertEquals(2_000L, summary.watchingMs)
        assertEquals(2L, summary.savedPostCount)
        assertEquals(listOf(1L, 1L), summary.savedSources.map { row -> row.count })
        assertEquals("First", summary.mostUsedCodex?.name)
        assertEquals(3L, summary.mostUsedCodex?.entryCount)
        assertEquals("Second", summary.leastUsedCodex?.name)
        assertEquals(0L, summary.leastUsedCodex?.entryCount)
    }

    @Test
    fun `source and tag percentages use post or search denominators without forcing a partition`() {
        val summary = StatisticsProjection.build(
            lifetime = LifetimeStatistics(
                watchedPostCount = 4L,
                watchedBySource = mapOf(SourceKey.PIXIV to 3L, SourceKey.GELBOORU to 1L),
                watchedByTag = mapOf(
                    StatisticsTagKey(SourceKey.PIXIV, "blue hair") to 3L,
                    StatisticsTagKey(SourceKey.PIXIV, "portrait") to 2L,
                ),
                searchCount = 2L,
                searchesBySource = mapOf(SourceKey.PIXIV to 2L, SourceKey.GELBOORU to 2L),
            )
        )

        assertEquals(listOf(75, 25), summary.watchedSources.map { row -> row.percentage })
        assertEquals(listOf(75, 50), summary.topWatchedTags.map { row -> row.percentage })
        assertEquals(listOf(100, 100), summary.searchSources.map { row -> row.percentage })
    }

    @Test
    fun `tag ranking is source aware deterministic and limited to five`() {
        val counts = (0 until 6).associate { index ->
            StatisticsTagKey(
                source = if (index == 0) SourceKey.GELBOORU else SourceKey.PIXIV,
                tag = "tag-$index",
            ) to (6L - index)
        }

        val rows = StatisticsProjection.build(
            lifetime = LifetimeStatistics(watchedPostCount = 10L, watchedByTag = counts)
        ).topWatchedTags

        assertEquals(5, rows.size)
        assertEquals(StatisticsTagKey(SourceKey.GELBOORU, "tag-0"), rows.first().key)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L), rows.map { row -> row.count })
    }

    @Test
    fun `empty Codex collection has explicit null rankings`() {
        val summary = StatisticsProjection.build(LifetimeStatistics())

        assertEquals(0L, summary.savedPostCount)
        assertNull(summary.mostUsedCodex)
        assertNull(summary.leastUsedCodex)
        assertEquals(emptyList<SourceStatistic>(), summary.topCodexSources)
    }

    @Test
    fun `duration format is stable and zero safe`() {
        assertEquals("0h 00m 00s", formatStatisticsDuration(-1L))
        assertEquals("27h 03m 09s", formatStatisticsDuration(97_389_000L))
    }

    private fun codex(id: String, name: String): Codex {
        return Codex(codexId = id, name = name, createdAtEpochMs = 1L)
    }

    private fun post(source: SourceKey, id: String, tags: List<String>): Post {
        return Post(
            id = PostId(source, id),
            preview = ImageRef(url = null, localPath = null, mime = null),
            full = null,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = tags,
            rawTags = tags,
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
