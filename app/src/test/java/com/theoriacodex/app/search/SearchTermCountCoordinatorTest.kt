package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTermCountCoordinatorTest {
    private val artist = SearchTerm("shared", SearchFacet.ARTIST, "artist")
    private val character = SearchTerm("shared", SearchFacet.CHARACTER, "character")
    private val female = SearchTerm("shared", SearchFacet.TAG, "female")

    @Test fun `nhentai and Hitomi counts retain facets and namespaces and reuse fresh decisions`() = runTest {
        for (source in listOf(SourceKey.NHENTAI, SourceKey.HITOMI)) {
            var calls = 0
            val terms = listOf(artist, character, female)
            val adapter = countedAdapter(source, terms) { scope ->
                calls++
                listOf(
                    FacetedTagSuggestion("shared neighbor", scope.facet!!, scope.sourceNamespace, 999),
                    FacetedTagSuggestion("shared", SearchFacet.GROUP, "group", 888),
                    FacetedTagSuggestion("shared", scope.facet!!, scope.sourceNamespace,
                        if (scope.facet == SearchFacet.ARTIST) 12 else 34),
                )
            }
            val owner = SearchTermCountCoordinator(TestRegistry(listOf(adapter)),
                dispatcher = StandardTestDispatcher(testScheduler))
            val expected = mapOf(artist to 12, character to 34, female to 34)
            assertEquals(expected, owner.fetch(source, terms))
            assertEquals(expected, owner.fetch(source, terms))
            assertEquals(3, calls)
        }
    }

    @Test fun `missing counts expire and do not borrow another namespace count`() = runTest {
        var calls = 0
        var now = 0L
        val adapter = countedAdapter(SourceKey.NHENTAI, listOf(artist)) {
            calls++
            listOf(FacetedTagSuggestion("shared", SearchFacet.CHARACTER, "character", 99))
        }
        val owner = SearchTermCountCoordinator(TestRegistry(listOf(adapter)), clock = { now },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(mapOf(artist to null), owner.fetch(SourceKey.NHENTAI, listOf(artist)))
        owner.fetch(SourceKey.NHENTAI, listOf(artist))
        assertEquals(1, calls)
        now = 60_001
        owner.fetch(SourceKey.NHENTAI, listOf(artist))
        assertEquals(2, calls)
    }

    @Test fun `typed lookup bounds concurrency and deadline and preserves parent cancellation`() = runTest {
        var active = 0
        var peak = 0
        var cancel = false
        val adapter = countedAdapter(SourceKey.HITOMI, listOf(artist, character, female)) {
            if (cancel) throw CancellationException("sheet left")
            active++
            peak = maxOf(peak, active)
            try { delay(20_000) } finally { active-- }
            emptyList()
        }
        val owner = SearchTermCountCoordinator(TestRegistry(listOf(adapter)),
            dispatcher = StandardTestDispatcher(testScheduler))
        val start = testScheduler.currentTime
        owner.fetch(SourceKey.HITOMI, listOf(artist, character, female))
        assertEquals(4_000L, testScheduler.currentTime - start)
        assertEquals(2, peak)
        assertEquals(0, active)
        cancel = true
        val next = artist.copy(value = "new")
        assertTrue(runCatching { owner.fetch(SourceKey.HITOMI, listOf(next)) }
            .exceptionOrNull() is CancellationException)
    }

    private fun countedAdapter(
        source: SourceKey,
        terms: List<SearchTerm>,
        lookup: suspend (FacetedSearchScope) -> List<FacetedTagSuggestion>,
    ): SourceAdapter = object : SourceAdapter by TestAdapter(source), FacetedSearchSourceAdapter {
        override val supportedSearchScopes = terms.map { FacetedSearchScope(it.facet, it.sourceNamespace) }.toSet()
        override suspend fun autocompleteFaceted(prefix: String, scope: FacetedSearchScope, limit: Int) = lookup(scope)
    }
}
