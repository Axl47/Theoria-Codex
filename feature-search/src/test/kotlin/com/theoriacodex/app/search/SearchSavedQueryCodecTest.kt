package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSavedQueryCodecTest {
    @Test
    fun `saved state round trips grouped positive terms`() {
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = emptyList(),
            excludeTerms = listOf(SearchTerm("blocked")),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        ).withIncludeTermGroups(
            listOf(
                SearchTermGroup.single(SearchTerm("tag1")),
                SearchTermGroup(listOf(SearchTerm("tag2"), SearchTerm("tag3"))),
            ),
        )

        assertEquals(query, SearchSavedQueryCodec.decode(SearchSavedQueryCodec.encode(query)))
    }
}
