package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * recent_searches.query_payload_json rows as the Gson encoder wrote them (HTML-escaped strings) must decode to the
 * same searches. legacy-query.json is the pre-versioned row shape: a bare stored Query.
 */
class RecentSearchPayloadGoldenTest {
    @Test
    fun `a Gson-written Multi-Search row decodes to the same search`() {
        val escaped = listOf(SearchTerm("tom & jerry's <cut>"), SearchTerm("a=b"))
        val artist = SearchTerm("é☃ > all", SearchFacet.ARTIST, "artist")

        assertEquals(
            RecentSearchPayload(
                query = Query(
                    mode = QueryMode.Unified,
                    includeTerms = escaped + artist,
                    excludeTerms = listOf(SearchTerm("english", SearchFacet.LANGUAGE, "language")),
                    sort = SortMode.NEWEST,
                    dateRange = DateRange(1_700_000_000_000L, 1_790_000_000_000L),
                    minScore = 10,
                    includeTermGroups = listOf(SearchTermGroup(escaped), SearchTermGroup.single(artist)),
                ),
                kind = RecentSearchKind.MULTI_SEARCH,
                sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
            ),
            RecentSearchPayloadCodec.decodeJson(golden("multi-search.json")),
        )
    }

    @Test
    fun `a Gson-written FYP row keeps its tags by source`() {
        assertEquals(
            RecentSearchPayload(
                query = Query(
                    mode = QueryMode.Unified,
                    includeTags = listOf("x"),
                    excludeTags = emptyList(),
                    sort = SortMode.TOP,
                    dateRange = null,
                    minScore = null,
                ),
                kind = RecentSearchKind.FYP,
                sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
                sourceTags = mapOf(
                    SourceKey.GELBOORU to listOf("rock & roll", "<seed>"),
                    SourceKey.PIXIV to listOf("it's=night"),
                ),
            ),
            RecentSearchPayloadCodec.decodeJson(golden("fyp.json")),
        )
    }

    @Test
    fun `a bare Query row infers its source metadata`() {
        val query = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("a & b"),
            excludeTags = listOf("c"),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = 5,
        )

        assertEquals(
            RecentSearchPayload(query, RecentSearchKind.SOURCE, listOf(SourceKey.PIXIV)),
            RecentSearchPayloadCodec.decodeJson(golden("legacy-query.json")),
        )
    }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/recent-search/$name")) { "missing golden $name" }.readText()
}
