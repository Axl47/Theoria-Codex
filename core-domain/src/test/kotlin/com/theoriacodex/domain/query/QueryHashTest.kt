package com.theoriacodex.domain.query

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryHashTest {
    @Test
    fun `hash remains stable when include and exclude terms are permuted`() {
        val first = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(
                SearchTerm("landscape"),
                SearchTerm("foo", SearchFacet.ARTIST, sourceNamespace = "artist"),
            ),
            excludeTerms = listOf(SearchTerm("lowres"), SearchTerm("comic")),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        val second = Query(
            mode = QueryMode.Unified,
            includeTerms = first.includeTerms.reversed(),
            excludeTerms = first.excludeTerms.reversed(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        assertEquals(QueryHash.from(first), QueryHash.from(second))
    }

    @Test
    fun `hash changes when sort changes`() {
        val newest = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(SearchTerm("landscape")),
            excludeTerms = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        val popular = newest.copy(sort = SortMode.POPULAR)

        assertNotEquals(QueryHash.from(newest), QueryHash.from(popular))
    }

    @Test
    fun `hash distinguishes facet namespace and polarity for identical text`() {
        val base = Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTerms = listOf(SearchTerm("najar")),
            excludeTerms = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
        val artist = base.copy(
            includeTerms = listOf(SearchTerm("najar", SearchFacet.ARTIST, sourceNamespace = "artist")),
        )
        val namespacedTag = base.copy(
            includeTerms = listOf(SearchTerm("najar", SearchFacet.TAG, sourceNamespace = "female")),
        )
        val excluded = base.copy(
            includeTerms = emptyList(),
            excludeTerms = listOf(SearchTerm("najar")),
        )

        val hashes = listOf(base, artist, namespacedTag, excluded).map(QueryHash::from)

        assertEquals(hashes.size, hashes.distinct().size)
        assertTrue(hashes.all { it.startsWith("v3|") })
    }

    @Test
    fun `hash preserves OR group boundaries while ignoring group order`() {
        val a = SearchTerm("a")
        val b = SearchTerm("b")
        val c = SearchTerm("c")
        val grouped = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(a, b, c),
            excludeTerms = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
            includeTermGroups = listOf(SearchTermGroup(listOf(a, b)), SearchTermGroup.single(c)),
        )
        val reordered = grouped.withIncludeTermGroups(
            listOf(SearchTermGroup.single(c), SearchTermGroup(listOf(b, a))),
        )
        val flat = grouped.withIncludeTermsAsRequired(listOf(a, b, c))

        assertEquals(QueryHash.from(grouped), QueryHash.from(reordered))
        assertNotEquals(QueryHash.from(grouped), QueryHash.from(flat))
    }

    @Test
    fun `hash normalizes term values and namespaces`() {
        val first = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(
                SearchTerm("  The   Idolmaster ", SearchFacet.SERIES, sourceNamespace = " PARODY "),
            ),
            excludeTerms = emptyList(),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = null,
        )
        val second = first.copy(
            includeTerms = listOf(
                SearchTerm("the idolmaster", SearchFacet.SERIES, sourceNamespace = "parody"),
            ),
        )

        assertEquals(QueryHash.from(first), QueryHash.from(second))
    }
}
