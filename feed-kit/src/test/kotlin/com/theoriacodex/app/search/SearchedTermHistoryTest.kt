package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Searched-term history from a completed search to the suggestions Search offers for a scope. */
class SearchedTermHistoryTest {
    @Test
    fun `typed searched terms are counted and suggested only in their own scope`() = runTest {
        val artistScope = FacetedSearchScope(SearchFacet.ARTIST, "artist")
        val adapter = FacetedAdapter(SourceKey.HITOMI, linkedSetOf(FacetedSearchScope.All, artistScope), emptyList(), emptyList())
        val statistics = InMemoryStatisticsRepository()
        val coordinator = testSearchCoordinator(TestRegistry(listOf(adapter, TestAdapter(SourceKey.PIXIV))), statisticsRepository = statistics)
        coordinator.initializeRoute()
        val artist = SearchTerm("najar", SearchFacet.ARTIST, "artist")
        val hitomi = SearchSourceScope.Single(SourceKey.HITOMI)
        val query = query(SourceKey.HITOMI, "nature").let { it.withIncludeTermsAsRequired(it.includeTerms + artist) }

        coordinator.recordAcceptedSearch(coordinator.executeInitial(query, hitomi) as SearchExecutionResult.Success)

        assertEquals(setOf(SearchTerm("nature"), artist), statistics.observeStatistics().first().searchesByTag.keys.mapTo(mutableSetOf()) { it.term })
        suspend fun searched(query: Query, sourceScope: SearchSourceScope, scope: FacetedSearchScope) =
            coordinator.fetchAutocomplete(query, sourceScope, scope, "na").searchedTags
        // Counts reach the coordinator through a flow collected off the test dispatcher.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (searched(query, hitomi, FacetedSearchScope.All).size < 2) delay(10) }
        }
        assertEquals(listOf(artist), searched(query, hitomi, artistScope))
        // Unified runs only portable general tags, so it never offers the artist.
        assertEquals(
            listOf(SearchTerm("nature")),
            searched(query.copy(mode = QueryMode.Unified), SearchSourceScope.Temporary(listOf(SourceKey.HITOMI, SourceKey.PIXIV)), FacetedSearchScope.All),
        )
    }
}
