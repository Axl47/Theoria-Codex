package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.Query

internal class SearchSessionStore(
    private val savedStateHandle: SavedStateHandle,
    private val coordinator: SearchCoordinator,
    private val scrollPersistence: SearchScrollPersistenceController,
) {
    val savedScroll: SearchScrollState? get() = savedStateHandle.savedSearchScrollState()

    fun markRestorationStarted() {
        savedStateHandle[SearchSavedStateKeys.RESTORATION_STARTED] = true
    }

    fun markRestorationCompleted() {
        savedStateHandle[SearchSavedStateKeys.RESTORATION_COMPLETED] = true
    }

    fun savedDraft(): Query? =
        savedStateHandle.get<String>(SearchSavedStateKeys.DRAFT_QUERY)?.let(SearchSavedQueryCodec::decode)

    suspend fun restoredScroll(query: SearchQueryUiState): SearchScrollState? =
        savedScroll ?: coordinator.restoreSearchScrollState(
            queryHash = query.appliedQueryHash,
            sourceScope = query.appliedSourceScope,
        )

    fun saveScroll(scroll: SearchScrollState) {
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = scroll.firstVisibleItemIndex
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = scroll.firstVisibleItemOffsetPx
    }

    fun persistScroll(query: SearchQueryUiState, scroll: SearchScrollState) {
        if (query.appliedSourceScope is SearchSourceScope.Temporary) return
        val queryHash = query.appliedQueryHash.takeIf(String::isNotBlank) ?: return
        scrollPersistence.submit(SearchScrollPersistenceTarget(queryHash = queryHash, state = scroll))
    }

    fun saveDraft(query: SearchQueryUiState) {
        if (query.draftSourceScope is SearchSourceScope.Temporary) return
        savedStateHandle[SearchSavedStateKeys.DRAFT_QUERY] = SearchSavedQueryCodec.encode(query.draft)
    }
}
