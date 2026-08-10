package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchPayloadCodecTest {
    @Test
    fun `source-aware payload round trips Multi-Search metadata`() {
        val query = query(QueryMode.Unified)
        val entry = RecentSearchEntry(
            query = query,
            queryHash = "hash",
            searchedAtEpochMs = 1L,
            kind = RecentSearchKind.MULTI_SEARCH,
            sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
        )

        val decoded = RecentSearchPayloadCodec.decodeJson(RecentSearchPayloadCodec.encodeJson(entry))

        assertEquals(query, decoded.query)
        assertEquals(RecentSearchKind.MULTI_SEARCH, decoded.kind)
        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV), decoded.sources)
    }

    @Test
    fun `legacy raw Query payload remains readable with inferred source metadata`() {
        val query = query(QueryMode.Source(SourceKey.PIXIV))

        val decoded = RecentSearchPayloadCodec.decodeJson(QueryStorageCodec.encodeJson(query))

        assertEquals(query, decoded.query)
        assertEquals(RecentSearchKind.SOURCE, decoded.kind)
        assertEquals(listOf(SourceKey.PIXIV), decoded.sources)
    }

    @Test
    fun `FYP payload round trips exact tags by source`() {
        val sourceTags = linkedMapOf(
            SourceKey.GELBOORU to listOf("gelbooru seed"),
            SourceKey.PIXIV to listOf("pixiv seed", "night"),
        )
        val entry = RecentSearchEntry(
            query = query(QueryMode.Unified),
            queryHash = "for_you:seed",
            searchedAtEpochMs = 1L,
            kind = RecentSearchKind.FYP,
            sources = sourceTags.keys.toList(),
            sourceTags = sourceTags,
        )

        val decoded = RecentSearchPayloadCodec.decodeJson(RecentSearchPayloadCodec.encodeJson(entry))

        assertEquals(sourceTags, decoded.sourceTags)
    }

    private fun query(mode: QueryMode) = Query(
        mode = mode,
        includeTags = listOf("landscape"),
        excludeTags = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
