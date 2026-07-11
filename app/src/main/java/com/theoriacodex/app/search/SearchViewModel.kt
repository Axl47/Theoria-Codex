package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.captureSearchCoordinatorSnapshot
import com.theoriacodex.app.search.state.toSearchUiState
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Navigation-scoped owner for Search.
 *
 * The coordinator remains the provider/domain engine. This owner is the only boundary that
 * schedules route work, publishes render state, persists compact reconstruction input, and emits
 * one-shot navigation/message effects. The application container may create this ViewModel but
 * must not retain it.
 */
internal class SearchViewModel(
    private val coordinator: SearchCoordinator,
    private val savedStateHandle: SavedStateHandle,
    private val autocompleteDelayMs: Long = DEFAULT_AUTOCOMPLETE_DELAY_MS,
    private val scrollPersistenceDelayMs: Long = DEFAULT_SCROLL_PERSISTENCE_DELAY_MS,
) : ViewModel(), RouteStateOwner<SearchUiState, SearchAction, SearchEffect> {
    private val mutableState = MutableStateFlow(
        coordinator.toSearchUiState(restoration = SearchRestorationUiState.NotStarted),
    )
    private val effectChannel = Channel<SearchEffect>(capacity = Channel.BUFFERED)

    override val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    override val effects: Flow<SearchEffect> = effectChannel.receiveAsFlow()

    private var nextRequestId = 0L
    private var nextAutocompleteGeneration = 0L
    private var activeRequestJob: Job? = null
    private var autocompleteJob: Job? = null
    private var trendingJob: Job? = null
    private var restorationJob: Job? = null
    private var scrollPersistenceJob: Job? = null
    private var latestEnvironmentSettings: AppSettings? = null

    override fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.SelectMode -> {
                coordinator.setMode(action.mode)
                coordinator.clearAutocompleteSuggestions()
                clearSuggestionState()
                publishCoordinatorState()
                persistDraftQuery()
                refreshTrending()
            }

            is SearchAction.SelectSort -> mutateDraft {
                coordinator.setSort(action.sort)
            }

            is SearchAction.SetDateRange -> mutateDraft {
                coordinator.setDateRange(action.range)
            }

            is SearchAction.SetMinimumScore -> mutateDraft {
                coordinator.setMinScore(action.score)
            }

            is SearchAction.SetDateRangePreset -> mutateDraft {
                coordinator.setDateRangePreset(action.preset)
            }

            is SearchAction.AddIncludeTerm -> mutateDraft {
                coordinator.addIncludeTerm(action.term)
            }

            is SearchAction.AddExcludeTerm -> mutateDraft {
                coordinator.addExcludeTerm(action.term)
            }

            is SearchAction.RemoveIncludeTerm -> mutateDraft {
                coordinator.removeIncludeTerm(action.term)
            }

            is SearchAction.RemoveExcludeTerm -> mutateDraft {
                coordinator.removeExcludeTerm(action.term)
            }

            is SearchAction.SelectSuggestionScope -> {
                if (coordinator.selectSearchScope(action.scope)) {
                    publishCoordinatorState()
                    refreshAutocomplete(mutableState.value.suggestions.input)
                }
            }

            is SearchAction.AutocompleteChanged -> refreshAutocomplete(action.input)

            is SearchAction.IncludeSuggestion -> mutateDraft {
                coordinator.addIncludeSuggestion(action.suggestion)
            }

            is SearchAction.ExcludeSuggestion -> mutateDraft {
                coordinator.addExcludeSuggestion(action.suggestion)
            }

            SearchAction.ClearAutocomplete -> {
                cancelAutocomplete()
                coordinator.clearAutocompleteSuggestions()
                clearSuggestionState()
                publishCoordinatorState()
            }

            SearchAction.ApplyDraft -> {
                val directGalleryId = commitPendingDirectInput()
                launchSearch(
                    kind = if (mutableState.value.content.hasExecutedSearch) {
                        SearchRequestKind.REPLACE
                    } else {
                        SearchRequestKind.INITIAL
                    },
                    submittedQuery = coordinator.draftQuery,
                    operation = coordinator::applyDraft,
                    onSuccess = {
                        directGalleryId?.let { galleryId -> openDirectNhentaiGallery(galleryId) }
                    },
                )
            }

            is SearchAction.ApplyHistoricalQuery -> launchSearch(
                kind = SearchRequestKind.REPLACE,
                submittedQuery = action.query,
                operation = {
                    check(coordinator.applyHistoricalQuery(action.query)) {
                        "Search source is unavailable"
                    }
                },
            )

            is SearchAction.ApplyTagSearch -> {
                if (!coordinator.prepareTagSearch(action.includeTags, mode = action.mode)) {
                    effectChannel.trySend(SearchEffect.ShowMessage("Search source is unavailable"))
                } else {
                    publishCoordinatorState()
                    persistDraftQuery()
                    launchSearch(
                        kind = SearchRequestKind.REPLACE,
                        submittedQuery = coordinator.draftQuery,
                        operation = coordinator::applyDraft,
                    )
                }
            }

            SearchAction.ResetDraft -> mutateDraft {
                coordinator.resetDraft()
            }

            SearchAction.ClearDraft -> mutateDraft {
                coordinator.clearDraft()
            }

            SearchAction.Retry -> launchSearch(
                kind = SearchRequestKind.RETRY,
                submittedQuery = coordinator.appliedQuery,
                operation = coordinator::retry,
            )

            SearchAction.LoadNextPage -> {
                if (mutableState.value.content.canLoadMore) {
                    launchSearch(
                        kind = SearchRequestKind.PAGE,
                        submittedQuery = coordinator.appliedQuery,
                        operation = coordinator::loadNextPage,
                    )
                }
            }

            SearchAction.CancelActiveRequest -> cancelActiveRequest()

            SearchAction.DismissError -> {
                coordinator.clearErrorMessage()
                mutableState.value = mutableState.value.copy(
                    content = mutableState.value.content.copy(error = null),
                )
            }

            SearchAction.DismissValidation -> {
                coordinator.clearTagInputValidationMessage()
                publishCoordinatorState()
            }

            SearchAction.Restore -> restore()

            SearchAction.Resume -> resumeSearchIfNeeded()

            is SearchAction.CommitTagInput -> commitTagInput(action.input)

            is SearchAction.AddPostIncludeTerm -> mutateDraft {
                coordinator.addPostIncludeTerm(action.post, action.term)
            }

            is SearchAction.AddPostExcludeTerm -> mutateDraft {
                coordinator.addPostExcludeTerm(action.post, action.term)
            }

            is SearchAction.SetNhentaiLanguage -> mutateDraft {
                coordinator.setNhentaiLanguageFilter(action.filter)
            }

            is SearchAction.SetNhentaiFullColor -> mutateDraft {
                coordinator.setNhentaiFullColorFilter(action.enabled)
            }

            SearchAction.ResetFilters -> mutateDraft {
                coordinator.resetFilters()
            }

            is SearchAction.RememberResolvedPost -> {
                coordinator.rememberResolvedPost(action.post)
                publishCoordinatorState()
            }

            is SearchAction.ScrollChanged -> persistScroll(action)

            is SearchAction.OpenResult -> publishReduction(
                SearchStateReducer.reduce(mutableState.value, action),
            )
        }
    }

    fun synchronizeEnvironment(settings: AppSettings) {
        latestEnvironmentSettings = settings
        if (!coordinator.isInitialized) return
        reconcileEnvironment(settings, scheduleRetry = true)
    }

    private fun reconcileEnvironment(settings: AppSettings, scheduleRetry: Boolean): Boolean {
        val settingsChanged = coordinator.onSettingsChanged(settings)
        val sourcesChanged = coordinator.onAvailableSourcesChanged()
        publishCoordinatorState()
        persistDraftQuery()
        val requiresRetry = settingsChanged || sourcesChanged
        if (requiresRetry && scheduleRetry) {
            launchSearch(
                kind = SearchRequestKind.RETRY,
                submittedQuery = coordinator.appliedQuery,
                operation = coordinator::retry,
            )
        }
        return requiresRetry
    }

    private fun mutateDraft(mutation: () -> Unit) {
        mutation()
        publishCoordinatorState()
        persistDraftQuery()
    }

    private fun commitTagInput(input: String) {
        if (!coordinator.commitTagInput(input)) {
            publishCoordinatorState()
            updateSuggestionInput(input)
            return
        }
        coordinator.clearAutocompleteSuggestions()
        clearSuggestionState()
        publishCoordinatorState()
        persistDraftQuery()
        val directGalleryId = coordinator.directNhentaiGalleryIdCandidate()
        if (input.trim().all(Char::isDigit) && directGalleryId != null) {
            launchSearch(
                kind = if (mutableState.value.content.hasExecutedSearch) {
                    SearchRequestKind.REPLACE
                } else {
                    SearchRequestKind.INITIAL
                },
                submittedQuery = coordinator.draftQuery,
                operation = coordinator::applyDraft,
                onSuccess = { openDirectNhentaiGallery(directGalleryId) },
            )
        }
    }

    private fun commitPendingDirectInput(): String? {
        val input = mutableState.value.suggestions.input.trim()
        if (input.isEmpty() || !input.all(Char::isDigit) || !coordinator.canCommitTagInput(input)) {
            return null
        }
        if (coordinator.commitTagInput(input)) {
            coordinator.clearAutocompleteSuggestions()
            clearSuggestionState()
            publishCoordinatorState()
            persistDraftQuery()
            return coordinator.directNhentaiGalleryIdCandidate()
        }
        return null
    }

    private suspend fun openDirectNhentaiGallery(galleryId: String) {
        val resolved = coordinator.results.firstOrNull { post ->
            post.id.source == SourceKey.NHENTAI && post.id.sourcePostId == galleryId
        } ?: coordinator.resolveNhentaiGalleryById(galleryId)
        if (resolved == null) {
            effectChannel.send(SearchEffect.ShowMessage("Could not open gallery $galleryId"))
            return
        }
        val context = ViewerLaunchContext(
            queryHash = "nhentai-search-id:$galleryId",
            startIndex = 0,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = 0,
        )
        coordinator.setViewerLaunchContext(context)
        effectChannel.send(
            SearchEffect.OpenViewer(
                posts = listOf(resolved),
                context = context,
                liveSearchBinding = false,
            ),
        )
    }

    private fun resumeSearchIfNeeded() {
        val current = mutableState.value
        if (
            current.execution.activeRequestId != null ||
            !current.content.hasExecutedSearch ||
            current.hasPendingChanges ||
            current.content.results.isNotEmpty()
        ) {
            return
        }
        launchSearch(
            kind = SearchRequestKind.RETRY,
            submittedQuery = coordinator.appliedQuery,
            operation = coordinator::restoreLastAppliedSearchIfNeeded,
        )
    }

    private fun launchSearch(
        kind: SearchRequestKind,
        submittedQuery: Query,
        operation: suspend () -> Unit,
        onSuccess: suspend () -> Unit = {},
    ) {
        cancelActiveRequest()
        val requestId = ++nextRequestId
        reduce(SearchStateChange.BeginRequest(requestId, kind, submittedQuery))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation()
                coroutineContext.ensureActive()
                if (completeRequest(requestId, kind)) onSuccess()
                persistDraftQuery()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failRequestIfCurrent(
                    requestId = requestId,
                    message = error.message ?: "Search failed",
                )
            } finally {
                if (!isActive) cancelRequestIfCurrent(requestId)
            }
        }
        activeRequestJob = job
        job.invokeOnCompletion {
            if (activeRequestJob === job) activeRequestJob = null
        }
        job.start()
    }

    private fun completeRequest(requestId: Long, kind: SearchRequestKind): Boolean {
        if (mutableState.value.execution.activeRequestId != requestId) return false
        val current = coordinatorState()
        mutableState.value = current
        val engineError = coordinator.errorMessage?.takeIf(String::isNotBlank)
        if (engineError != null) {
            reduce(
                SearchStateChange.RequestFailed(
                    requestId = requestId,
                    message = engineError,
                    statuses = coordinator.statuses,
                ),
            )
            return false
        }
        val snapshot = coordinator.captureSearchCoordinatorSnapshot()
        reduce(
            if (kind == SearchRequestKind.PAGE) {
                SearchStateChange.AppendPage(
                    requestId = requestId,
                    results = snapshot.results,
                    statuses = snapshot.statuses,
                    canLoadMore = snapshot.canLoadMore,
                )
            } else {
                SearchStateChange.ReplaceResults(
                    requestId = requestId,
                    appliedQuery = snapshot.appliedQuery,
                    results = snapshot.results,
                    statuses = snapshot.statuses,
                    canLoadMore = snapshot.canLoadMore,
                )
            },
        )
        return true
    }

    private fun cancelActiveRequest() {
        val requestId = mutableState.value.execution.activeRequestId
        activeRequestJob?.cancel(CancellationException("Search route request cancelled"))
        activeRequestJob = null
        if (requestId != null) {
            mutableState.value = coordinatorState()
            reduce(SearchStateChange.RequestCancelled(requestId))
        }
    }

    private fun cancelRequestIfCurrent(requestId: Long) {
        if (mutableState.value.execution.activeRequestId == requestId) {
            reduce(SearchStateChange.RequestCancelled(requestId))
        }
    }

    private fun failRequestIfCurrent(requestId: Long, message: String) {
        if (mutableState.value.execution.activeRequestId == requestId) {
            reduce(SearchStateChange.RequestFailed(requestId, message))
        }
    }

    private fun refreshAutocomplete(input: String) {
        cancelAutocomplete()
        updateSuggestionInput(input)
        val generation = ++nextAutocompleteGeneration
        autocompleteJob = viewModelScope.launch {
            if (autocompleteDelayMs > 0) delay(autocompleteDelayMs)
            if (input.isBlank()) {
                coordinator.clearAutocompleteSuggestions()
                coordinator.refreshFeaturedFacetedSuggestions()
            } else {
                coordinator.refreshAutocompleteSuggestions(input)
            }
            coroutineContext.ensureActive()
            if (generation != nextAutocompleteGeneration) return@launch
            publishAutocompleteState(input)
        }
    }

    private fun cancelAutocomplete() {
        nextAutocompleteGeneration += 1L
        autocompleteJob?.cancel(CancellationException("Autocomplete superseded"))
        autocompleteJob = null
    }

    private fun refreshTrending() {
        trendingJob?.cancel(CancellationException("Trending refresh superseded"))
        trendingJob = viewModelScope.launch {
            coordinator.loadTrendingTags()
            coroutineContext.ensureActive()
            val trending = coordinator.captureSearchCoordinatorSnapshot().trendingTags
            val current = mutableState.value
            mutableState.value = current.copy(
                suggestions = current.suggestions.copy(trending = trending),
            )
        }
    }

    private fun restore() {
        if (restorationJob?.isActive == true) return
        if (mutableState.value.restoration is SearchRestorationUiState.Restored) return
        reduce(SearchStateChange.BeginRestoration)
        savedStateHandle[SearchSavedStateKeys.RESTORATION_STARTED] = true
        restorationJob = viewModelScope.launch {
            try {
                coordinator.initialize()
                val environmentChanged = latestEnvironmentSettings?.let { settings ->
                    reconcileEnvironment(settings, scheduleRetry = false)
                } == true
                val savedQuery = savedStateHandle.get<String>(SearchSavedStateKeys.DRAFT_QUERY)
                    ?.let(SearchSavedQueryCodec::decode)
                val restoredQuery = savedQuery?.let(coordinator::restoreDraftQuery) == true
                val savedScroll = savedScrollState()
                val scroll = savedScroll ?: coordinator.restoreSearchScrollState()
                mutableState.value = coordinatorState(
                    includeSuggestions = true,
                    restoration = SearchRestorationUiState.Restored(
                        restoredQuery = restoredQuery,
                        scrollState = scroll,
                    ),
                )
                savedStateHandle[SearchSavedStateKeys.RESTORATION_COMPLETED] = true
                persistDraftQuery()
                refreshTrending()
                if (environmentChanged) {
                    launchSearch(
                        kind = SearchRequestKind.RETRY,
                        submittedQuery = coordinator.appliedQuery,
                        operation = coordinator::retry,
                    )
                } else {
                    resumeSearchIfNeeded()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reduce(
                    SearchStateChange.RestorationFailed(
                        error.message ?: "Could not restore Search",
                    ),
                )
            }
        }
    }

    private fun persistScroll(action: SearchAction.ScrollChanged) {
        val index = action.firstVisibleItemIndex.coerceAtLeast(0)
        val offset = action.firstVisibleItemOffsetPx.coerceAtLeast(0)
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = index
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = offset
        scrollPersistenceJob?.cancel()
        scrollPersistenceJob = viewModelScope.launch {
            if (scrollPersistenceDelayMs > 0) delay(scrollPersistenceDelayMs)
            coordinator.persistSearchScrollState(index = index, offsetPx = offset)
        }
    }

    private fun savedScrollState(): SearchScrollState? {
        val index = savedStateHandle.get<Int>(SearchSavedStateKeys.SCROLL_INDEX) ?: return null
        val offset = savedStateHandle.get<Int>(SearchSavedStateKeys.SCROLL_OFFSET) ?: 0
        return SearchScrollState(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    private fun persistDraftQuery() {
        savedStateHandle[SearchSavedStateKeys.DRAFT_QUERY] =
            SearchSavedQueryCodec.encode(coordinator.draftQuery)
    }

    private fun updateSuggestionInput(input: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            suggestions = current.suggestions.copy(
                input = input,
                canCommitInput = coordinator.canCommitTagInput(input),
            ),
        )
    }

    private fun clearSuggestionState() {
        val current = mutableState.value
        mutableState.value = current.copy(
            suggestions = current.suggestions.copy(
                input = "",
                autocomplete = emptyList(),
                facetedAutocomplete = emptyList(),
                canCommitInput = false,
            ),
        )
    }

    private fun publishAutocompleteState(input: String) {
        val snapshot = coordinator.captureSearchCoordinatorSnapshot()
        val current = mutableState.value
        val mapped = coordinatorState()
        mutableState.value = mapped.copy(
            query = mapped.query.copy(
                selectedScope = snapshot.selectedSearchScope,
                validationMessage = snapshot.tagInputValidationMessage,
            ),
            suggestions = current.suggestions.copy(
                input = input,
                autocomplete = snapshot.autocompleteSuggestions,
                facetedAutocomplete = snapshot.facetedAutocompleteSuggestions,
                canCommitInput = coordinator.canCommitTagInput(input),
            ),
        )
    }

    private fun publishCoordinatorState(includeSuggestions: Boolean = false) {
        mutableState.value = coordinatorState(includeSuggestions = includeSuggestions)
    }

    private fun coordinatorState(
        includeSuggestions: Boolean = false,
        restoration: SearchRestorationUiState = mutableState.value.restoration,
    ): SearchUiState {
        val current = mutableState.value
        val mapped = coordinator.toSearchUiState(
            restoration = restoration,
            executionOverride = current.execution,
        )
        return mapped.copy(
            content = mapped.content.copy(
                displayVersion = maxOf(
                    current.content.displayVersion,
                    mapped.content.displayVersion,
                ),
            ),
            suggestions = if (includeSuggestions) {
                mapped.suggestions.copy(input = current.suggestions.input)
            } else {
                current.suggestions
            },
        )
    }

    private fun reduce(change: SearchStateChange) {
        mutableState.value = SearchStateReducer.reduce(mutableState.value, change).state
    }

    private fun publishReduction(reduction: com.theoriacodex.app.search.state.SearchReduction) {
        mutableState.value = reduction.state
        reduction.effects.forEach(::publishEffect)
    }

    private fun publishEffect(effect: SearchEffect) {
        when (effect) {
            is SearchEffect.OpenViewer -> viewModelScope.launch {
                coordinator.setViewerLaunchContext(effect.context)
                effectChannel.send(effect)
            }

            is SearchEffect.ShowMessage -> effectChannel.trySend(effect)
        }
    }

    override fun onCleared() {
        effectChannel.close()
        super.onCleared()
    }

    companion object {
        fun factory(coordinator: SearchCoordinator): ViewModelProvider.Factory {
            return viewModelFactory {
                initializer {
                    SearchViewModel(
                        coordinator = coordinator,
                        savedStateHandle = createSavedStateHandle(),
                    )
                }
            }
        }

        private const val DEFAULT_AUTOCOMPLETE_DELAY_MS = 300L
        private const val DEFAULT_SCROLL_PERSISTENCE_DELAY_MS = 150L
    }
}
