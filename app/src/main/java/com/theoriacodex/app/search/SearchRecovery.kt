package com.theoriacodex.app.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.media.reconnections
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

internal fun searchViewModelFactory(
    coordinator: SearchCoordinator,
    relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
    networkOnline: Flow<Boolean> = emptyFlow(),
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SearchViewModel(
            coordinator = coordinator,
            savedStateHandle = createSavedStateHandle(),
            relatedPostsLoader = relatedPostsLoader,
            reconnections = networkOnline.reconnections(),
        )
    }
}

/** Retries what failed while offline, one source at a time so each keeps its own continuation. */
internal suspend fun retrySearchAfterReconnect(
    state: StateFlow<SearchUiState>,
    retry: () -> Unit,
    retrySource: (SourceKey) -> Job?,
) {
    // A request begun offline may still be failing; waiting lets its outcome get this retry too.
    val settled = state.first { it.execution.activeRequestId == null }
    if (settled.content.error != null) {
        retry()
        return
    }
    settled.content.statuses.filter { it.retriesOnReconnect() }.forEach { status ->
        state.first { it.execution.activeRequestId == null }
        retrySource(status.source)?.join()
    }
}
