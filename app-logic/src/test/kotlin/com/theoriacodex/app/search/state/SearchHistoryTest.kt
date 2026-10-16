package com.theoriacodex.app.search.state

import com.theoriacodex.domain.model.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryTest {
    @Test fun `only changed accepted searches add a bounded backward step`() {
        var state = SearchUiState()
        state = accepted(state, "first")
        assertFalse(state.canUndoSearch)
        repeat(30) { state = accepted(state, "query-$it") }
        assertEquals(20, state.searchHistory.size)
        assertEquals("query-9", state.searchHistory.first().query.includeTags.single())
        val same = accepted(state, "query-29")
        assertEquals(state.searchHistory, same.searchHistory)
        assertTrue(same.canUndoSearch)
        assertFalse(same.copy(execution = same.execution.copy(activeRequestId = 10)).canUndoSearch)
    }

    @Test fun `Undo consumes steps without adding a forward entry`() {
        var state = accepted(accepted(accepted(SearchUiState(), "a"), "b"), "c")
        state = accepted(state, "b", undo = true)
        assertEquals(listOf("a"), state.searchHistory.map { it.query.includeTags.single() })
        state = accepted(state, "a", undo = true)
        assertFalse(state.canUndoSearch)
        state = accepted(state, "d")
        assertEquals(listOf("a"), state.searchHistory.map { it.query.includeTags.single() })
    }

    private fun accepted(previous: SearchUiState, tag: String, undo: Boolean = false): SearchUiState = previous.copy(
        query = previous.query.copy(applied = emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm(tag)))),
        content = previous.content.copy(hasExecutedSearch = true),
    ).withAcceptedSearchHistory(previous, undo)
}
