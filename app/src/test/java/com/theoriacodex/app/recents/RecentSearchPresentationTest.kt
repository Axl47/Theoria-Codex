package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecentSearchPresentationTest {
    @Test
    fun `source search uses comma-separated tags without a sort or subtitle`() {
        val presentation = recentSearchPresentation(
            entry(
                query = query(QueryMode.Source(SourceKey.PIXIV)),
                kind = RecentSearchKind.SOURCE,
                sources = listOf(SourceKey.PIXIV),
            )
        )

        assertEquals("landscape, night, -ai", presentation.title)
        assertNull(presentation.subtitle)
        assertFalse(presentation.title.contains("Newest"))
    }

    @Test
    fun `Unified and Multi-Search subtitles name participating sources`() {
        val sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV)

        assertEquals(
            "Gelbooru, Pixiv",
            recentSearchPresentation(entry(query(), RecentSearchKind.UNIFIED, sources)).subtitle,
        )
        assertEquals(
            "Multi-Search · Gelbooru, Pixiv",
            recentSearchPresentation(entry(query(), RecentSearchKind.MULTI_SEARCH, sources)).subtitle,
        )
        assertEquals(
            "Gelbooru, Pixiv",
            recentSearchPresentation(entry(query(), RecentSearchKind.FYP, sources)).subtitle,
        )
    }

    @Test
    fun `FYP replay preserves source-specific tags and falls back for legacy entries`() {
        val sourceTags = mapOf(
            SourceKey.GELBOORU to listOf("gelbooru seed"),
            SourceKey.PIXIV to listOf("pixiv seed"),
        )
        val current = entry(query(), RecentSearchKind.FYP, sourceTags.keys.toList())
            .copy(sourceTags = sourceTags)
        val legacy = entry(query(), RecentSearchKind.FYP, sourceTags.keys.toList())

        assertEquals(sourceTags, current.fypSeedBySource())
        assertEquals(
            sourceTags.keys.associateWith { legacy.query.includeTags },
            legacy.fypSeedBySource(),
        )
    }

    private fun entry(
        query: Query,
        kind: RecentSearchKind,
        sources: List<SourceKey>,
    ) = RecentSearchEntry(query, "hash", 1L, kind, sources)

    private fun query(mode: QueryMode = QueryMode.Unified) = Query(
        mode = mode,
        includeTags = listOf("landscape", "night"),
        excludeTags = listOf("ai"),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
