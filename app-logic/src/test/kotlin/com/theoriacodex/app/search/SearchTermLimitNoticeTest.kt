package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.SearchTermLimit
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTermLimitNoticeTest {
    private val limits = mapOf(SourceKey.DANBOORU to SearchTermLimit(maxTerms = 2, rankedSortUsesTerm = true))
    private val sources = listOf(SourceKey.GELBOORU, SourceKey.DANBOORU)

    @Test
    fun `unified warns about a capped source while a single-source search is refused`() {
        val tags = listOf("cat", "sky", "cloud")

        assertEquals(
            listOf(SearchTermLimitNotice(SourceKey.DANBOORU, 2, blocksSearch = false, reducedBySort = false)),
            searchTermLimitNotices(query(QueryMode.Unified, tags), sources, limits),
        )
        assertEquals(
            listOf(SearchTermLimitNotice(SourceKey.DANBOORU, 2, blocksSearch = true, reducedBySort = false)),
            searchTermLimitNotices(query(QueryMode.Source(SourceKey.DANBOORU), tags), listOf(SourceKey.DANBOORU), limits),
        )
    }

    @Test
    fun `queries within the budget and terms Unified never sends produce no notice`() {
        assertEquals(emptyList<SearchTermLimitNotice>(),
            searchTermLimitNotices(query(QueryMode.Unified, listOf("cat", "sky")), sources, limits))
        val withSourceTerm = query(QueryMode.Unified, listOf("cat", "sky")).let {
            it.copy(excludeTerms = listOf(SearchTerm("artist_name", SearchFacet.ARTIST, "artist")))
        }
        assertEquals(emptyList<SearchTermLimitNotice>(), searchTermLimitNotices(withSourceTerm, sources, limits))
    }

    @Test
    fun `a ranked sort lowers the budget and says so`() {
        val notice = searchTermLimitNotices(query(QueryMode.Unified, listOf("cat", "sky"), SortMode.TOP), sources, limits)
        assertEquals(listOf(SearchTermLimitNotice(SourceKey.DANBOORU, 1, blocksSearch = false, reducedBySort = true)), notice)
    }

    private fun query(mode: QueryMode, tags: List<String>, sort: SortMode = SortMode.NEWEST) =
        Query(mode = mode, includeTags = tags, excludeTags = emptyList(), sort = sort, dateRange = null, minScore = null)
}
