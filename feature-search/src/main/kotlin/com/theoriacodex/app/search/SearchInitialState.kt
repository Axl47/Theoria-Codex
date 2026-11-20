package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/** One initial query and its execution identity are shared by draft and applied state. */
internal fun initialSearchState(sources: List<SourceKey>, execution: SearchExecutionService): SearchUiState {
    val query = emptySearchQuery()
    return SearchUiState(
        query = SearchQueryUiState(
            draft = query,
            applied = query,
            appliedQueryHash = execution.executionKeyFor(query, SearchSourceScope.GlobalUnified),
            availableSources = sources,
            modeOptions = listOf(QueryMode.Unified) + sources.map(QueryMode::Source),
            enabledSourceCount = sources.size,
        ),
    )
}
