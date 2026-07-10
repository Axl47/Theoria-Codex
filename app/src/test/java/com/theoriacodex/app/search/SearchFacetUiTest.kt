package com.theoriacodex.app.search

import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFacetUiTest {
    @Test
    fun `scope labels use app vocabulary instead of provider namespaces`() {
        assertEquals("All", searchScopeLabel(FacetedSearchScope.All))
        assertEquals(
            "Series",
            searchScopeLabel(FacetedSearchScope(SearchFacet.SERIES, sourceNamespace = "parody")),
        )
        assertEquals(
            "Types",
            searchScopeLabel(FacetedSearchScope(SearchFacet.TYPE, sourceNamespace = "category")),
        )
    }

    @Test
    fun `term chips preserve facet namespace and polarity`() {
        assertEquals(
            "animated",
            searchTermChipLabel(SearchTerm("animated"), excluded = false),
        )
        assertEquals(
            "− animated",
            searchTermChipLabel(SearchTerm("animated"), excluded = true),
        )
        assertEquals(
            "Artist · najar",
            searchTermChipLabel(
                SearchTerm("najar", SearchFacet.ARTIST, sourceNamespace = "artist"),
                excluded = false,
            ),
        )
        assertEquals(
            "− Female tag · x-ray",
            searchTermChipLabel(
                SearchTerm("x-ray", SearchFacet.TAG, sourceNamespace = "female"),
                excluded = true,
            ),
        )
    }

    @Test
    fun `suggestion metadata includes typed label and formatted count`() {
        assertEquals(
            "Artist • 12345",
            facetedSuggestionMetaLabel(
                FacetedTagSuggestion(
                    text = "najar",
                    facet = SearchFacet.ARTIST,
                    sourceNamespace = "artist",
                    count = 12_345,
                ),
            ),
        )
    }
}
