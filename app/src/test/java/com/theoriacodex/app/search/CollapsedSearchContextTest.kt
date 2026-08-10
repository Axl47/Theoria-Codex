package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedSearchContextTest {
    @Test
    fun `summary prioritizes applied source query and filter count`() {
        val query = emptySearchQuery(QueryMode.Source(SourceKey.PIXIV)).copy(
            includeTerms = listOf(SearchTerm("klee"), SearchTerm("landscape")),
        )

        assertEquals(
            "Pixiv · klee · 2 filters",
            collapsedSearchContextSummary(
                appliedSourceScope = SearchSourceScope.Single(SourceKey.PIXIV),
                appliedQuery = query,
                activeFilterCount = 2,
            ),
        )
    }

    @Test
    fun `summary compacts temporary sources and supports excluded only queries`() {
        val query = emptySearchQuery().copy(excludeTerms = listOf(SearchTerm("comic")))

        assertEquals(
            "Gelbooru +2 · -comic",
            collapsedSearchContextSummary(
                appliedSourceScope = SearchSourceScope.Temporary(
                    listOf(SourceKey.GELBOORU, SourceKey.HITOMI, SourceKey.PIXIV),
                ),
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
        assertEquals("Unified", collapsedSearchContextSummary(SearchSourceScope.GlobalUnified, emptySearchQuery(), 0))
    }
}
