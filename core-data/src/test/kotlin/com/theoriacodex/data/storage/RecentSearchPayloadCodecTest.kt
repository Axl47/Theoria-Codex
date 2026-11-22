package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
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
    fun `a retired For You payload reads as Unified with tags for every source it searched`() {
        val stored = """{"recentSearchSchemaVersion":1,"query":{"schemaVersion":2,"modeType":"unified",""" +
            """"includeTags":["night"],"excludeTags":[],"sort":"TOP"},"kind":"FYP","sources":["GELBOORU","PIXIV"],""" +
            """"sourceTags":{"PIXIV":["night"]}}"""

        val decoded = RecentSearchPayloadCodec.decodeJson(stored)

        assertEquals(RecentSearchKind.UNIFIED, decoded.kind)
        assertEquals(mapOf(SourceKey.GELBOORU to emptyList(), SourceKey.PIXIV to listOf("night")), decoded.sourceTags)
    }

    @Test
    fun `query payload round trips positive group boundaries`() {
        val grouped = query(QueryMode.Unified).withIncludeTermGroups(
            listOf(
                SearchTermGroup.single(SearchTerm("landscape")),
                SearchTermGroup(listOf(SearchTerm("night"), SearchTerm("sunset"))),
            ),
        )

        val restored = RecentSearchPayloadCodec.decodeJson(
            RecentSearchPayloadCodec.encodeJson(grouped, RecentSearchKind.UNIFIED, listOf(SourceKey.PIXIV)),
        ).query

        assertEquals(grouped, restored)
        assertEquals(grouped.effectiveIncludeTermGroups, restored.effectiveIncludeTermGroups)
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
