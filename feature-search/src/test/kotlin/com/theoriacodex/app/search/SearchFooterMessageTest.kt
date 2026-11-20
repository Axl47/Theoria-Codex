package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchErrorUiState
import com.theoriacodex.app.search.state.SearchExecutionUiState
import com.theoriacodex.app.search.state.SearchRequest
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchFooterMessageTest {
    private val ended = SearchUiState(
        content = SearchContentUiState(
            results = listOf(testPost(SourceKey.GELBOORU, "1")),
            statuses = listOf(SourceRunStatus(SourceKey.GELBOORU, SourceRunState.SUCCESS)),
            canLoadMore = false,
            hasExecutedSearch = true,
        ),
    )

    private fun footer(state: SearchUiState, resolvingDurations: Boolean = false, hasVisibleResults: Boolean = true) =
        searchFooterMessage(state, resolvingDurations, hasVisibleResults)

    private fun SearchUiState.running(kind: SearchRequestKind) =
        copy(execution = SearchExecutionUiState(SearchRequest(1, kind, query.applied)))

    @Test
    fun `a search with no further page says so`() {
        assertEquals("No more results", footer(ended))
    }

    @Test
    fun `a search with more pages shows nothing`() {
        assertNull(footer(ended.copy(content = ended.content.copy(canLoadMore = true))))
    }

    @Test
    fun `a running search or page shows nothing`() {
        assertNull(footer(ended.running(SearchRequestKind.REPLACE)))
        assertNull(footer(ended.running(SearchRequestKind.PAGE)))
    }

    @Test
    fun `a failed request or source is not the end`() {
        val error = SearchErrorUiState("Gelbooru returned malformed JSON", SearchRequestKind.PAGE, retryable = true)
        assertNull(footer(ended.copy(content = ended.content.copy(error = error))))
        val failedSource = SourceRunStatus(SourceKey.RULE34XXX, SourceRunState.FAILED)
        assertNull(footer(ended.copy(content = ended.content.copy(statuses = ended.content.statuses + failedSource))))
    }

    @Test
    fun `resolving durations wins over the end`() {
        assertEquals("Resolving durations…", footer(ended, resolvingDurations = true))
    }

    @Test
    fun `no visible results leave the message to the empty state`() {
        assertNull(footer(ended, hasVisibleResults = false))
        assertNull(footer(ended.copy(content = ended.content.copy(hasExecutedSearch = false))))
    }
}
