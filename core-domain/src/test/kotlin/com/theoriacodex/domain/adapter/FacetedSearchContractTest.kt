package com.theoriacodex.domain.adapter

import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetedSearchContractTest {
    @Test
    fun `all scope is distinct from tag and provider namespace scopes`() {
        val tags = FacetedSearchScope(facet = SearchFacet.TAG)
        val femaleTags = FacetedSearchScope(
            facet = SearchFacet.TAG,
            sourceNamespace = "female",
        )

        assertTrue(FacetedSearchScope.All.isAll)
        assertFalse(tags.isAll)
        assertFalse(femaleTags.isAll)
        assertEquals(SearchFacet.TAG, femaleTags.facet)
        assertEquals("female", femaleTags.sourceNamespace)
    }

    @Test
    fun `faceted suggestion converts to a canonical search term`() {
        val suggestion = FacetedTagSuggestion(
            text = "the idolmaster",
            facet = SearchFacet.SERIES,
            sourceNamespace = "parody",
            count = 120,
        )

        assertEquals(
            SearchTerm("the idolmaster", SearchFacet.SERIES, "parody"),
            suggestion.toSearchTerm(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `namespace-only scope is rejected`() {
        FacetedSearchScope(sourceNamespace = "female")
    }
}
