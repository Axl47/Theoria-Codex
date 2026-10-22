package com.theoriacodex.app.search.state

import com.theoriacodex.domain.model.Query
import com.theoriacodex.data.repository.SearchScrollState

/** Compact route-session history; result pages and provider continuations are never retained. */
data class SearchHistoryEntry(
    val query: Query,
    val sourceScope: SearchSourceScope,
    val scrollState: SearchScrollState = SearchScrollState(0, 0),
)

/** Called only after an admitted explicit root search has committed and published its applied query. */
fun SearchUiState.withAcceptedSearchHistory(
    previous: SearchUiState,
    undo: Boolean,
    previousScrollState: SearchScrollState = SearchScrollState(0, 0),
): SearchUiState {
    val next = SearchHistoryEntry(query.applied, query.appliedSourceScope)
    val prior = SearchHistoryEntry(previous.query.applied, previous.query.appliedSourceScope, previousScrollState)
    val history = when {
        undo -> previous.searchHistory.dropLast(1)
        !previous.content.hasExecutedSearch ||
            (prior.query == next.query && prior.sourceScope == next.sourceScope) -> previous.searchHistory
        else -> (previous.searchHistory + prior).takeLast(20)
    }
    return copy(searchHistory = history)
}
