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
import kotlinx.coroutines.flow.emptyFlow

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
    state: () -> SearchUiState,
    retry: () -> Unit,
    retrySource: (SourceKey) -> Job?,
) {
    val current = state()
    if (current.execution.activeRequestId != null) return
    if (current.content.error != null) {
        retry()
        return
    }
    current.content.statuses.filter { it.retriesOnReconnect() }.forEach { status ->
        if (state().execution.activeRequestId != null) return
        retrySource(status.source)?.join()
    }
}
