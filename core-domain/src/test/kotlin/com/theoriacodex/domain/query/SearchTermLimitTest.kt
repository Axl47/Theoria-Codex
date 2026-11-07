package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTermLimitTest {
    private val guestLimit = SearchTermLimit(maxTerms = 2, rankedSortUsesTerm = true)

    @Test
    fun `ranked sorts spend one term on their order metatag`() {
        assertEquals(2, guestLimit.budgetFor(SortMode.NEWEST))
        assertEquals(1, guestLimit.budgetFor(SortMode.TOP))
        assertEquals(1, guestLimit.budgetFor(SortMode.POPULAR))
        assertEquals(2, SearchTermLimit(maxTerms = 2).budgetFor(SortMode.TOP))
    }

    @Test
    fun `an or group costs one term because its alternatives are requested separately`() {
        val query = query(groups = listOf(listOf("cat", "dog"), listOf("sky")), excluded = emptyList())
        assertEquals(2, query.requestTermCount)
        assertTrue(guestLimit.allows(query))
        assertFalse(guestLimit.allows(query.copy(sort = SortMode.TOP)))
    }

    @Test
    fun `limiting keeps required groups before exclusions`() {
        val query = query(groups = listOf(listOf("cat"), listOf("sky")), excluded = listOf("comic"))

        val limited = query.withinTermLimit(guestLimit)
        assertEquals(listOf("cat", "sky"), limited.includeTags)
        assertEquals(emptyList<String>(), limited.excludeTags)

        val one = query(groups = listOf(listOf("cat")), excluded = listOf("comic", "text")).withinTermLimit(guestLimit)
        assertEquals(listOf("cat"), one.includeTags)
        assertEquals(listOf("comic"), one.excludeTags)
        assertEquals(query, query.withinTermLimit(null))
    }

    private fun query(groups: List<List<String>>, excluded: List<String>) = Query(
        mode = QueryMode.Source(SourceKey.DANBOORU),
        includeTermGroups = groups.map { terms -> SearchTermGroup(terms.map(::SearchTerm)) },
        excludeTerms = excluded.map(::SearchTerm),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
