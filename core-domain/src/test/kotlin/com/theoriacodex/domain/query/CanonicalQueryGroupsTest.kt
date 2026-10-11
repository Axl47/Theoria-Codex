package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.normalizedForMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalQueryGroupsTest {
    @Test fun `copies derive flat terms from groups and divergent legacy construction is rejected`() {
        val a = SearchTerm("a")
        val b = SearchTerm("b")
        val group = SearchTermGroup(listOf(a, b))
        val query = Query(QueryMode.Unified, listOf(group), emptyList(), SortMode.NEWEST, null, null)
        assertEquals(listOf(a, b), query.includeTerms)
        val replaced = query.copy(includeTermGroups = listOf(SearchTermGroup.single(a)))
        assertEquals(listOf(a), replaced.includeTerms)
        assertEquals(listOf(group), query.effectiveIncludeTermGroups)
        assertTrue(runCatching { Query(QueryMode.Unified, includeTerms = listOf(a), excludeTerms = emptyList(),
            SortMode.NEWEST, null, null, includeTermGroups = listOf(group)) }.isFailure)
    }

    @Test fun `one mode normalization policy preserves portable groups and reports removed source terms`() {
        val portable = SearchTermGroup(listOf(SearchTerm("a"), SearchTerm("b")))
        val owned = SearchTermGroup.single(SearchTerm("artist", SearchFacet.ARTIST, "artist"))
        val query = Query(QueryMode.Source(SourceKey.HITOMI), listOf(portable, owned),
            listOf(SearchTerm("excluded")), SortMode.NEWEST, null, null)
        val normalized = query.normalizedForMode(QueryMode.Unified)
        assertTrue(normalized.removedSourceOwnedTerms)
        assertEquals(listOf(portable), normalized.query.includeTermGroups)
        assertEquals(query.excludeTerms, normalized.query.excludeTerms)
        assertEquals(query, query.normalizedForMode(query.mode).query)
    }
}
