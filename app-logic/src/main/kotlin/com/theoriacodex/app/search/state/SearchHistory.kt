package com.theoriacodex.app.search.state

import com.theoriacodex.domain.model.Query

/** Compact route-session history; result pages and provider continuations are never retained. */
data class SearchHistoryEntry(val query: Query, val sourceScope: SearchSourceScope)

/** Called only after an admitted explicit root search has committed and published its applied query. */
fun SearchUiState.withAcceptedSearchHistory(previous: SearchUiState, undo: Boolean): SearchUiState {
    val next = SearchHistoryEntry(query.applied, query.appliedSourceScope)
    val prior = SearchHistoryEntry(previous.query.applied, previous.query.appliedSourceScope)
    val history = when {
        undo -> previous.searchHistory.dropLast(1)
        !previous.content.hasExecutedSearch || prior == next -> previous.searchHistory
        else -> (previous.searchHistory + prior).takeLast(20)
    }
    return copy(searchHistory = history)
}
