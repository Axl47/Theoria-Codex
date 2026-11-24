package com.theoriacodex.app.search

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchExecutionContractTest {
    private val query = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm("cat")))

    @Test
    fun `a search key ignores terms the scope's mode cannot run (ADR 0080)`() {
        val withArtist = query.withIncludeTermsAsRequired(listOf(SearchTerm("cat"), SearchTerm("someone", SearchFacet.ARTIST, "artist")))

        assertEquals(
            searchExecutionKey(query, SearchSourceScope.GlobalUnified),
            searchExecutionKey(withArtist, SearchSourceScope.GlobalUnified),
        )
    }

    @Test
    fun `an answer counts as completed only when it succeeded and a source answered, even with no posts (ADR 0080)`() {
        fun status(state: SourceRunState) = SourceRunStatus(SourceKey.PIXIV, state)
        val continuation = SearchContinuation("key", SearchSourceScope.GlobalUnified, emptySet(), emptyList(), FeedCursor(query))
        fun success(vararg states: SourceRunState) = SearchExecutionResult.Success(
            "key", query, SearchSourceScope.GlobalUnified, emptyList(), states.map(::status), continuation,
        )

        assertTrue(success(SourceRunState.FAILED, SourceRunState.SUCCESS).completedSuccessfully)
        assertFalse(success(SourceRunState.FAILED, SourceRunState.EXCLUDED).completedSuccessfully)
        assertFalse(
            SearchExecutionResult.Failure("key", query, SearchSourceScope.GlobalUnified, listOf(status(SourceRunState.SUCCESS)), "offline")
                .completedSuccessfully,
        )
    }
}
