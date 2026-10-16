package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedSearchContextTest {
    @Test
    fun `summary shows applied query and filter count`() {
        val query = emptySearchQuery(QueryMode.Source(SourceKey.PIXIV)).withIncludeTermsAsRequired(listOf(SearchTerm("klee"), SearchTerm("landscape")))

        assertEquals(
            "klee & landscape · 2 filters",
            collapsedSearchContextSummary(
                appliedQuery = query,
                activeFilterCount = 2,
            ),
        )
    }

    @Test
    fun `summary renders grouped applied terms`() {
        val query = emptySearchQuery().copy(includeTermGroups = listOf(
                SearchTermGroup.single(SearchTerm("tag1")),
                SearchTermGroup(listOf(SearchTerm("tag2"), SearchTerm("tag3"))),
            ))

        assertEquals(
            "tag1 & (tag2 || tag3)",
            collapsedSearchContextSummary(query, 0),
        )
    }

    @Test
    fun `summary supports excluded only queries`() {
        val query = emptySearchQuery().copy(excludeTerms = listOf(SearchTerm("comic")))

        assertEquals(
            "-comic",
            collapsedSearchContextSummary(
                appliedQuery = query,
                activeFilterCount = 0,
            ),
        )
    }

    @Test
    fun `filter count includes query and route local filters`() {
        val query = emptySearchQuery().copy(sort = SortMode.POPULAR, minScore = 10)

        assertEquals(
            6,
            activeSearchFilterCount(
                appliedQuery = query,
                animatedOnly = true,
                animatedDurationActive = false,
                hideLiked = true,
                hideSaved = false,
                hideWatched = true,
                fullColor = false,
                language = NhentaiLanguageFilter.ENGLISH,
            ),
        )
        assertEquals(null, collapsedSearchContextSummary(emptySearchQuery(), 0))
    }
}
