package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.Query

internal class SearchSessionStore(private val savedStateHandle: SavedStateHandle) {
    val savedScroll: SearchScrollState? get() = savedStateHandle.savedSearchScrollState()

    fun savedDraft(): Query? =
        savedStateHandle.get<String>(SearchSavedStateKeys.DRAFT_QUERY)?.let(SearchSavedQueryCodec::decode)

    fun saveScroll(scroll: SearchScrollState) {
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = scroll.firstVisibleItemIndex
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = scroll.firstVisibleItemOffsetPx
    }

    fun saveDraft(query: SearchQueryUiState) {
        if (query.draftSourceScope is SearchSourceScope.Temporary) return
        savedStateHandle[SearchSavedStateKeys.DRAFT_QUERY] = SearchSavedQueryCodec.encode(query.draft)
    }
}
