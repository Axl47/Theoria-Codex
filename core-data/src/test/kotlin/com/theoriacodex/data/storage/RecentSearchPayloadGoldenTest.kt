package com.theoriacodex.data.storage

import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchPayloadGoldenTest {
    @Test
    fun `a Gson-written Multi-Search row decodes to the same search`() {
        assertEquals(multiSearch, RecentSearchPayloadCodec.decodeJson(golden("multi-search.json")))
    }

    @Test
    fun `a Gson-written For You row reads as Unified and keeps its tags by source (ADR 0038)`() {
        assertEquals(fyp, RecentSearchPayloadCodec.decodeJson(golden("fyp.json")))
    }

    @Test
    fun `a bare Query row infers its source metadata (ADR 0038)`() {
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

    @Test
    fun `a preset is stored as its days and loads back relative, and stored dates stay fixed (ADR 0038, 0084)`() {
        assertEquals(lastWeek, RecentSearchPayloadCodec.decodeJson(golden("last-days.json")))
        val fixedWeek = lastWeek.query.copy(dateRange = DateRange.Between(1_699_395_200_000L, 1_700_000_000_000L))
        val stored = golden("fixed-week.json")
        assertEquals(lastWeek.copy(query = fixedWeek), RecentSearchPayloadCodec.decodeJson(stored))
        assertEquals(stored, RecentSearchPayloadCodec.decodeJson(stored).run { RecentSearchPayloadCodec.encodeJson(query, kind, sources) })
    }

    @Test
    fun `the goldens hold every stored key`() {
        val source = RecentSearchPayload(
            query = Query(
                mode = QueryMode.Source(SourceKey.PIXIV),
                includeTerms = listOf(SearchTerm("a & b", SearchFacet.ARTIST, "artist")),
                excludeTerms = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            kind = RecentSearchKind.SOURCE,
            sources = listOf(SourceKey.PIXIV),
        )
        val animated = RecentSearchPayload(
            query = Query(
                mode = QueryMode.Source(SourceKey.DANBOORU),
                includeTags = listOf("cat"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ).copy(media = MediaFilter.ANIMATED),
            kind = RecentSearchKind.SOURCE,
            sources = listOf(SourceKey.DANBOORU),
        )
        assertStoredFormat(
            golden("multi-search.json") to record(multiSearch),
            golden("fyp.json") to record(fyp).copy(kind = "FYP"),
            golden("source.json") to record(source),
            golden("animated.json") to record(animated),
            golden("last-days.json") to record(lastWeek),
        )
    }

    private fun record(payload: RecentSearchPayload) = RecentSearchPayloadRecord(
        recentSearchSchemaVersion = 1,
        query = QueryStorageCodec.encode(payload.query),
        kind = payload.kind.name,
        sources = payload.sources.map { it.id },
        sourceTags = payload.sourceTags.mapKeys { it.key.id },
    )

    private val multiSearch = run {
        val escaped = listOf(SearchTerm("tom & jerry's <cut>"), SearchTerm("a=b"))
        val artist = SearchTerm("é☃ > all", SearchFacet.ARTIST, "artist")
        RecentSearchPayload(
            query = Query(
                mode = QueryMode.Unified,
                includeTerms = escaped + artist,
                excludeTerms = listOf(SearchTerm("english", SearchFacet.LANGUAGE, "language")),
                sort = SortMode.NEWEST,
                dateRange = DateRange.Between(1_700_000_000_000L, 1_790_000_000_000L),
                minScore = 10,
                includeTermGroups = listOf(SearchTermGroup(escaped), SearchTermGroup.single(artist)),
            ),
            kind = RecentSearchKind.MULTI_SEARCH,
            sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
        )
    }

    private val lastWeek = RecentSearchPayload(
        query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("cat"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = DateRange.LastDays(7),
            minScore = null,
        ),
        kind = RecentSearchKind.UNIFIED,
        sources = listOf(SourceKey.DANBOORU, SourceKey.GELBOORU),
    )

    private val fyp = RecentSearchPayload(
        query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("x"),
            excludeTags = emptyList(),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = null,
        ),
        kind = RecentSearchKind.UNIFIED,
        sources = listOf(SourceKey.GELBOORU, SourceKey.PIXIV),
        sourceTags = mapOf(
            SourceKey.GELBOORU to listOf("rock & roll", "<seed>"),
            SourceKey.PIXIV to listOf("it's=night"),
        ),
    )

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/recent-search/$name")) { "missing golden $name" }.readText()
}
