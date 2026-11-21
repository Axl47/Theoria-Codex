package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.withAutocompleteResult
import com.theoriacodex.app.search.state.withSuggestionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps Search's tag suggestions current: local matches first, then the providers, with newer typing replacing older requests. */
internal class SearchSuggestionRefresher(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SearchUiState>,
    private val coordinator: SearchCoordinator,
    private val dispatcher: CoroutineDispatcher,
    private val autocompleteDelayMs: Long,
) {
    private var nextAutocompleteGeneration = 0L
    private var autocompleteJob: Job? = null
    private var trendingJob: Job? = null

    fun refreshAutocomplete(input: String) {
        cancelAutocomplete()
        if (input.isNotBlank()) {
            trendingJob?.cancel(CancellationException("Interactive autocomplete superseded trending refresh"))
            trendingJob = null
        }
        state.value = state.value.withSuggestionInput(input)
        val generation = ++nextAutocompleteGeneration
        val localState = state.value
        autocompleteJob = scope.launch {
            val cached = withContext(dispatcher) {
                coordinator.suggestions.cachedAutocomplete(
                    localState.query.draft, localState.query.draftSources,
                    localState.query.selectedScope, input,
                )
            }
            publishAutocompleteIfCurrent(generation, cached)
            if (autocompleteDelayMs > 0) delay(autocompleteDelayMs)
            val result = withContext(dispatcher) {
                coordinator.suggestions.fetchAutocomplete(
                    localState.query.draft, localState.query.draftSources,
                    localState.query.selectedScope, input,
                    onUpdate = { update ->
                        withContext(Dispatchers.Main.immediate) { publishAutocompleteIfCurrent(generation, update) }
                    },
                )
            }
            coroutineContext.ensureActive()
            publishAutocompleteIfCurrent(generation, result)
        }
    }

    private fun publishAutocompleteIfCurrent(generation: Long, result: SearchAutocompleteResult) {
        if (generation != nextAutocompleteGeneration) return
        state.value = state.value.withAutocompleteResult(result)
    }

    fun cancelAutocomplete() {
        nextAutocompleteGeneration += 1L
        autocompleteJob?.cancel(CancellationException("Autocomplete superseded"))
        autocompleteJob = null
    }

    /** Refreshes the draft's sources' trending tags in the tag store, where source autocomplete finds them. */
    fun refreshTrending() {
        trendingJob?.cancel(CancellationException("Trending refresh superseded"))
        val query = state.value.query
        trendingJob = scope.launch {
            withContext(dispatcher) { coordinator.suggestions.fetchTrending(query.draft, query.draftSources) }
        }
    }
}
