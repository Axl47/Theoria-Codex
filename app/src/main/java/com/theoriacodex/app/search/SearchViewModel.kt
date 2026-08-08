package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchDraftContext
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.modeKey
import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.media.NoOpAnimatedDurationEnricher
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Navigation-scoped owner for Search.
 *
 * The coordinator remains the provider/domain engine. This owner is the only boundary that
 * schedules route work, publishes render state, persists compact reconstruction input, and emits
 * one-shot navigation/message effects. The application container may create this ViewModel but
 * must not retain it.
 */
@Suppress("LargeClass") // One route authority is intentional; F15 owns hotspot-size ratchets.
internal class SearchViewModel(
    private val coordinator: SearchCoordinator,
    private val savedStateHandle: SavedStateHandle,
    private val executionService: SearchExecutionService = coordinator,
    private val autocompleteDelayMs: Long = DEFAULT_AUTOCOMPLETE_DELAY_MS,
    scrollPersistenceDelayMs: Long = DEFAULT_SCROLL_PERSISTENCE_DELAY_MS,
    scrollPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val animatedDurationEnricher: AnimatedDurationEnricher = NoOpAnimatedDurationEnricher,
) : ViewModel(), RouteStateOwner<SearchUiState, SearchAction, SearchEffect> {
    private val initialSources = coordinator.availableSources
    private val initialQuery = com.theoriacodex.app.search.state.emptySearchQuery()
    private val mutableState = MutableStateFlow(
        SearchUiState(
            query = SearchQueryUiState(
                draft = initialQuery,
                applied = initialQuery,
                appliedQueryHash = executionService.executionKeyFor(
                    initialQuery,
                    SearchSourceScope.GlobalUnified,
                ),
                availableSources = initialSources,
                modeOptions = listOf(QueryMode.Unified) + initialSources.map(QueryMode::Source),
                enabledSourceCount = initialSources.size,
            ),
            restoration = SearchRestorationUiState.NotStarted,
        ),
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
    private var activeContinuation: SearchContinuation? = null
    private var latestEnvironmentSettings: AppSettings? = null
    private val appliedByMode = mutableMapOf<String, Query>()
    private val scrollPersistence = SearchScrollPersistenceController(
        dispatcher = scrollPersistenceDispatcher,
        debounceMillis = scrollPersistenceDelayMs,
        persist = { target ->
            coordinator.persistSearchScrollState(
                index = target.state.firstVisibleItemIndex,
                offsetPx = target.state.firstVisibleItemOffsetPx,
                queryHash = target.queryHash,
            )
        },
    )
    private val durationEnrichmentOwner = SearchAnimatedDurationEnrichmentOwner(
        scope = viewModelScope, enricher = animatedDurationEnricher,
        currentState = { mutableState.value }, applyResolvedPosts = ::applyResolvedPosts,
    )

    init {
        addCloseable(SEARCH_SCROLL_PERSISTENCE_KEY, scrollPersistence)
    }

    override fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.SelectMode -> selectMode(action.mode)

            is SearchAction.ToggleTemporarySource -> toggleTemporarySource(action.source)

            is SearchAction.SelectSort -> mutateDraft {
                SearchDraftReducer.mutateQuery(it) { query -> query.copy(sort = action.sort) }.state
            }

            is SearchAction.SetDateRange -> mutateDraft {
                SearchDraftReducer.mutateQuery(it) { query -> query.copy(dateRange = action.range) }.state
            }

            is SearchAction.SetMinimumScore -> mutateDraft {
                SearchDraftReducer.mutateQuery(it) { query -> query.copy(minScore = action.score) }.state
            }

            is SearchAction.SetDateRangePreset -> mutateDraft {
                val now = System.currentTimeMillis()
                val day = 24L * 60L * 60L * 1000L
                val range = when (action.preset) {
                    DateRangePreset.NONE -> null
                    DateRangePreset.TODAY -> com.theoriacodex.domain.model.DateRange(now - day, now)
                    DateRangePreset.LAST_7_DAYS -> com.theoriacodex.domain.model.DateRange(now - 7L * day, now)
                    DateRangePreset.LAST_30_DAYS -> com.theoriacodex.domain.model.DateRange(now - 30L * day, now)
                }
                SearchDraftReducer.mutateQuery(it) { query -> query.copy(dateRange = range) }.state
            }

            is SearchAction.AddIncludeTerm -> mutateDraft {
                SearchDraftReducer.addTerm(it, action.term, excluded = false).state
            }

            is SearchAction.AddExcludeTerm -> mutateDraft {
                SearchDraftReducer.addTerm(it, action.term, excluded = true).state
            }

            is SearchAction.RemoveIncludeTerm -> mutateDraft {
                SearchDraftReducer.removeTerm(it, action.term, excluded = false).state
            }

            is SearchAction.RemoveExcludeTerm -> mutateDraft {
                SearchDraftReducer.removeTerm(it, action.term, excluded = true).state
            }

            is SearchAction.SelectSuggestionScope -> {
                val reduction = SearchDraftReducer.selectScope(mutableState.value, action.scope)
                if (reduction.accepted) {
                    mutableState.value = reduction.state
                    refreshAutocomplete(mutableState.value.suggestions.input)
                }
            }

            is SearchAction.AutocompleteChanged -> refreshAutocomplete(action.input)

            is SearchAction.IncludeSuggestion -> mutateDraft {
                SearchDraftReducer.addTerm(it, action.suggestion.toSearchTerm(), excluded = false).state
            }

            is SearchAction.ExcludeSuggestion -> mutateDraft {
                SearchDraftReducer.addTerm(it, action.suggestion.toSearchTerm(), excluded = true).state
            }

            SearchAction.ClearAutocomplete -> {
                cancelAutocomplete()
                clearSuggestionState()
            }

            SearchAction.ApplyDraft -> applyDraft()

            is SearchAction.ApplyHistoricalQuery -> applyHistoricalQuery(action.query)

            is SearchAction.ApplyTagSearch -> applyTagSearch(action)

            SearchAction.ResetDraft -> mutateDraft {
                SearchDraftReducer.resetDraft(it).state
            }

            SearchAction.ClearDraft -> mutateDraft {
                SearchDraftReducer.clearDraft(it).state
            }

            SearchAction.Retry -> mutableState.value.let { current ->
                launchRootSearch(
                    kind = SearchRequestKind.RETRY,
                    query = current.query.applied,
                    sourceScope = current.query.appliedSourceScope,
                    persistAcceptedResult = false,
                )
            }

            SearchAction.LoadNextPage -> loadNextPage()

            SearchAction.CancelActiveRequest -> cancelActiveRequest()

            SearchAction.DismissError -> {
                mutableState.value = mutableState.value.copy(
                    content = mutableState.value.content.copy(error = null),
                )
            }

            SearchAction.DismissValidation -> {
                mutableState.value = mutableState.value.copy(
                    query = mutableState.value.query.copy(validationMessage = null),
                )
            }

            SearchAction.Restore -> restore()

            SearchAction.Resume -> {
                requestRouteEntryScrollRestoration()
                resumeSearchIfNeeded()
            }

            is SearchAction.CommitTagInput -> commitTagInput(action.input)

            is SearchAction.AddPostIncludeTerm -> mutateDraft {
                SearchDraftReducer.addPostTerm(
                    it, action.post, action.term, excluded = false,
                    availableSources = it.query.availableSources.toSet(),
                    supportedScopes = coordinator::supportedSearchScopes,
                ).state
            }

            is SearchAction.AddPostExcludeTerm -> mutateDraft {
                SearchDraftReducer.addPostTerm(
                    it, action.post, action.term, excluded = true,
                    availableSources = it.query.availableSources.toSet(),
                    supportedScopes = coordinator::supportedSearchScopes,
                ).state
            }

            is SearchAction.SetNhentaiLanguage -> mutateDraft {
                SearchDraftReducer.setNhentaiLanguage(it, action.filter).state
            }

            is SearchAction.SetNhentaiFullColor -> mutateDraft {
                SearchDraftReducer.setNhentaiFullColor(it, action.enabled).state
            }

            SearchAction.ResetFilters -> mutateDraft {
                SearchDraftReducer.mutateQuery(it) { query ->
                    query.copy(sort = com.theoriacodex.domain.model.SortMode.NEWEST, dateRange = null, minScore = null)
                }.state
            }

            is SearchAction.RememberResolvedPost -> {
                applyResolvedPosts(mutableState.value.query.appliedQueryHash, listOf(action.post))
            }

            is SearchAction.RequestAnimatedDurationEnrichment -> durationEnrichmentOwner.request(action.queryHash)

            is SearchAction.ScrollChanged -> persistScroll(action)

            is SearchAction.ScrollRestorationApplied -> reduce(
                SearchStateChange.ScrollRestorationApplied(action.requestId),
            )

            is SearchAction.OpenResult -> publishReduction(
                SearchStateReducer.reduce(mutableState.value, action),
            )
        }
    }

    private fun selectMode(mode: QueryMode) {
        mutableState.value = SearchDraftReducer.selectMode(
            state = mutableState.value,
            mode = mode,
            context = draftContext(),
            supportedScopes = coordinator::supportedSearchScopes,
        ).state
        persistDraftQuery()
        refreshTrending()
    }

    private fun toggleTemporarySource(source: SourceKey) {
        val input = mutableState.value.suggestions.input
        val reduction = SearchDraftReducer.toggleTemporarySource(
            state = mutableState.value,
            source = source,
            context = draftContext(),
            supportedScopes = coordinator::supportedSearchScopes,
        )
        if (!reduction.accepted) return
        cancelAutocomplete()
        mutableState.value = reduction.state.copy(
            suggestions = reduction.state.suggestions.copy(
                input = "",
                canCommitInput = false,
            ),
        )
        persistDraftQuery()
        if (input.isBlank()) refreshTrending()
        refreshAutocomplete(input)
    }

    private fun applyDraft() {
        val directGalleryId = commitPendingDirectInput()
        val current = mutableState.value
        launchRootSearch(
            kind = if (current.content.hasExecutedSearch) {
                SearchRequestKind.REPLACE
            } else {
                SearchRequestKind.INITIAL
            },
            query = current.query.draft,
            sourceScope = current.query.draftSourceScope,
            persistAcceptedResult = true,
            onSuccess = {
                directGalleryId?.let { galleryId -> openDirectNhentaiGallery(galleryId) }
            },
        )
    }

    private fun applyTagSearch(action: SearchAction.ApplyTagSearch) {
        val reduction = SearchDraftReducer.prepareTagSearch(
            state = mutableState.value,
            includeTags = action.includeTags,
            excludeTags = emptyList(),
            mode = action.mode,
            availableSources = mutableState.value.query.availableSources.toSet(),
            supportedScopes = coordinator::supportedSearchScopes,
        )
        if (!reduction.accepted) {
            effectChannel.trySend(SearchEffect.ShowMessage("Search source is unavailable"))
            return
        }
        mutableState.value = reduction.state
        persistDraftQuery()
        val current = mutableState.value
        launchRootSearch(
            kind = SearchRequestKind.REPLACE,
            query = current.query.draft,
            sourceScope = current.query.draftSourceScope,
            persistAcceptedResult = true,
        )
    }

    private fun applyHistoricalQuery(query: Query) {
        val reduction = SearchDraftReducer.restoreDraft(
            state = mutableState.value,
            query = query,
            availableSources = mutableState.value.query.availableSources.toSet(),
            supportedScopes = coordinator::supportedSearchScopes,
        )
        if (!reduction.accepted) {
            effectChannel.trySend(SearchEffect.ShowMessage("Search source is unavailable"))
            return
        }
        mutableState.value = reduction.state
        persistDraftQuery()
        val current = mutableState.value
        launchRootSearch(
            kind = SearchRequestKind.REPLACE,
            query = current.query.draft,
            sourceScope = current.query.draftSourceScope,
            persistAcceptedResult = true,
        )
    }

    private fun loadNextPage() {
        if (!mutableState.value.content.canLoadMore) return
        val continuation = activeContinuation ?: return
        launchPage(continuation)
    }

    fun synchronizeEnvironment(settings: AppSettings) {
        latestEnvironmentSettings = settings
        if (!coordinator.isInitialized) return
        reconcileEnvironment(settings, scheduleRetry = true)
    }

    private fun reconcileEnvironment(settings: AppSettings, scheduleRetry: Boolean): Boolean {
        val hadExecutedSearch = mutableState.value.content.hasExecutedSearch
        val change = coordinator.updateEnvironment(settings)
        val current = mutableState.value
        val available = change.availableSources.toSet()
        val draftScope = current.query.draftSourceScope.reconciledWith(available)
        val appliedScope = current.query.appliedSourceScope.reconciledWith(available)
        val draft = current.query.draft.reconciledWith(draftScope)
        val applied = current.query.applied.reconciledWith(appliedScope)
        val scopes = coordinator.supportedSearchScopes(draft.mode)
        val enabledCount = when (draftScope) {
            SearchSourceScope.GlobalUnified -> settings.runtime.enabledSources.intersect(available).size
            is SearchSourceScope.Single -> setOf(draftScope.source).intersect(available).size
            is SearchSourceScope.Temporary -> draftScope.sources.toSet().intersect(available).size
        }
        mutableState.value = current.copy(
            query = current.query.copy(
                draft = draft,
                applied = applied,
                draftSourceScope = draftScope,
                appliedSourceScope = appliedScope,
                availableSources = change.availableSources,
                modeOptions = listOf(QueryMode.Unified) + change.availableSources.map(QueryMode::Source),
                enabledSourceCount = enabledCount,
                supportedScopes = scopes,
                selectedScope = current.query.selectedScope.takeIf { it in scopes }
                    ?: com.theoriacodex.domain.adapter.FacetedSearchScope.All,
            ),
        )
        persistDraftQuery()
        val requiresRetry = hadExecutedSearch && (change.settingsChanged || change.sourcesChanged ||
            draft != current.query.draft || applied != current.query.applied)
        if (requiresRetry && scheduleRetry) {
            val current = mutableState.value
            launchRootSearch(
                kind = SearchRequestKind.RETRY,
                query = current.query.applied,
                sourceScope = current.query.appliedSourceScope,
                persistAcceptedResult = false,
            )
        }
        return requiresRetry
    }

    private fun mutateDraft(mutation: (SearchUiState) -> SearchUiState) {
        mutableState.value = mutation(mutableState.value)
        persistDraftQuery()
    }

    private fun commitTagInput(input: String) {
        val reduction = SearchDraftReducer.commitInput(mutableState.value, input)
        if (!reduction.accepted) {
            mutableState.value = reduction.state
            updateSuggestionInput(input)
            return
        }
        mutableState.value = reduction.state
        clearSuggestionState()
        persistDraftQuery()
        val directGalleryId = SearchDraftReducer.directNhentaiGalleryIdCandidate(
            mutableState.value.query.draft,
        )
        if (input.trim().all(Char::isDigit) && directGalleryId != null) {
            val current = mutableState.value
            launchRootSearch(
                kind = if (current.content.hasExecutedSearch) {
                    SearchRequestKind.REPLACE
                } else {
                    SearchRequestKind.INITIAL
                },
                query = current.query.draft,
                sourceScope = current.query.draftSourceScope,
                persistAcceptedResult = true,
                onSuccess = { openDirectNhentaiGallery(directGalleryId) },
            )
        }
    }

    private fun commitPendingDirectInput(): String? {
        val input = mutableState.value.suggestions.input.trim()
        if (input.isEmpty() || !input.all(Char::isDigit) ||
            !SearchDraftReducer.canCommitInput(mutableState.value, input)
        ) {
            return null
        }
        val reduction = SearchDraftReducer.commitInput(mutableState.value, input)
        if (reduction.accepted) {
            mutableState.value = reduction.state
            clearSuggestionState()
            persistDraftQuery()
            return SearchDraftReducer.directNhentaiGalleryIdCandidate(mutableState.value.query.draft)
        }
        return null
    }

    private suspend fun openDirectNhentaiGallery(galleryId: String) {
        val resolved = mutableState.value.content.results.firstOrNull { post ->
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
        launchRootSearch(
            kind = SearchRequestKind.RETRY,
            query = current.query.applied,
            sourceScope = current.query.appliedSourceScope,
            persistAcceptedResult = false,
        )
    }

    private fun launchRootSearch(
        kind: SearchRequestKind,
        query: Query,
        sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
        persistAcceptedResult: Boolean,
        onSuccess: suspend () -> Unit = {},
    ) {
        cancelActiveRequest()
        activeContinuation = null
        val expectedExecutionKey = executionService.executionKeyFor(query, sourceScope)
        val requestId = ++nextRequestId
        reduce(SearchStateChange.BeginRequest(requestId, kind, query))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = executionService.executeInitial(query, sourceScope)
                coroutineContext.ensureActive()
                if (!isAdmittedResult(requestId, result, expectedExecutionKey)) {
                    activeContinuation = null
                    cancelRequestIfCurrent(requestId)
                    return@launch
                }
                if (persistAcceptedResult) {
                    executionService.persistAppliedSearch(
                        query = result.query,
                        sourceScope = result.sourceScope,
                        executionKey = result.executionKey,
                    )
                }
                if (completeRequest(requestId, result, expectedExecutionKey)) {
                    onSuccess()
                }
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

    private fun completeRequest(
        requestId: Long,
        result: SearchExecutionResult,
        expectedExecutionKey: String,
    ): Boolean {
        if (mutableState.value.execution.activeRequestId != requestId) return false
        if (result.executionKey != expectedExecutionKey) {
            activeContinuation = null
            reduce(SearchStateChange.RequestCancelled(requestId))
            return false
        }
        return when (result) {
            is SearchExecutionResult.Failure -> {
                activeContinuation = null
                rememberAppliedQuery(result.query, result.sourceScope)
                reduce(
                    SearchStateChange.RequestFailed(
                        requestId = requestId,
                        message = result.message,
                        statuses = result.statuses,
                        appliedQuery = result.query,
                        appliedSourceScope = result.sourceScope,
                        appliedQueryHash = result.executionKey,
                    ),
                )
                false
            }

            is SearchExecutionResult.Success -> {
                activeContinuation = result.continuation
                rememberAppliedQuery(result.query, result.sourceScope)
                reduce(
                    SearchStateChange.ReplaceResults(
                        requestId = requestId,
                        appliedQuery = result.query,
                        appliedSourceScope = result.sourceScope,
                        appliedQueryHash = result.executionKey,
                        results = result.posts,
                        statuses = result.statuses,
                        canLoadMore = result.continuation.canLoadMore,
                    ),
                )
                true
            }
        }
    }

    private fun rememberAppliedQuery(query: Query, sourceScope: SearchSourceScope) {
        if (sourceScope !is SearchSourceScope.Temporary) {
            appliedByMode[modeKey(query.mode)] = query
        }
    }

    private fun isAdmittedResult(
        requestId: Long,
        result: SearchExecutionResult,
        expectedExecutionKey: String,
    ): Boolean {
        return mutableState.value.execution.activeRequestId == requestId &&
            result.executionKey == expectedExecutionKey
    }

    private fun launchPage(continuation: SearchContinuation) {
        cancelActiveRequest()
        val requestId = ++nextRequestId
        reduce(SearchStateChange.BeginRequest(requestId, SearchRequestKind.PAGE, continuation.query))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = executionService.executePage(continuation)
                coroutineContext.ensureActive()
                if (!isAdmittedPage(requestId, result, continuation)) {
                    cancelRequestIfCurrent(requestId)
                    return@launch
                }
                completePageRequest(requestId, result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failRequestIfCurrent(requestId, error.message ?: "Could not load more results")
            } finally {
                if (!isActive) cancelRequestIfCurrent(requestId)
            }
        }
        activeRequestJob = job
        job.invokeOnCompletion { if (activeRequestJob === job) activeRequestJob = null }
        job.start()
    }

    private fun isAdmittedPage(
        requestId: Long,
        result: SearchPageResult,
        continuation: SearchContinuation,
    ): Boolean = mutableState.value.execution.activeRequestId == requestId &&
        result.executionKey == continuation.executionKey &&
        mutableState.value.query.appliedQueryHash == continuation.executionKey

    private fun completePageRequest(requestId: Long, result: SearchPageResult) {
        when (result) {
            is SearchPageResult.Failure -> reduce(
                SearchStateChange.RequestFailed(requestId, result.message, result.statuses),
            )
            is SearchPageResult.Success -> {
                activeContinuation = result.continuation
                reduce(
                    SearchStateChange.AppendPage(
                        requestId,
                        result.posts,
                        result.statuses,
                        result.continuation.canLoadMore,
                    ),
                )
            }
        }
    }

    private fun cancelActiveRequest() {
        val requestId = mutableState.value.execution.activeRequestId
        activeRequestJob?.cancel(CancellationException("Search route request cancelled"))
        activeRequestJob = null
        if (requestId != null) {
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
            val requestState = mutableState.value
            val result = coordinator.fetchAutocomplete(
                query = requestState.query.draft,
                sourceScope = requestState.query.draftSourceScope,
                selectedScope = requestState.query.selectedScope,
                input = input,
                trending = requestState.suggestions.trending,
            )
            coroutineContext.ensureActive()
            if (generation != nextAutocompleteGeneration) return@launch
            val current = mutableState.value
            mutableState.value = current.copy(
                query = current.query.copy(
                    selectedScope = result.selectedScope,
                    validationMessage = result.validationMessage,
                ),
                suggestions = current.suggestions.copy(
                    input = result.input,
                    autocomplete = result.autocomplete,
                    facetedAutocomplete = result.facetedAutocomplete,
                    canCommitInput = SearchDraftReducer.canCommitInput(
                        current.copy(
                            suggestions = current.suggestions.copy(autocomplete = result.autocomplete),
                        ),
                        result.input,
                    ),
                ),
            )
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
            val requestState = mutableState.value
            val trending = coordinator.fetchTrending(
                query = requestState.query.draft,
                sourceScope = requestState.query.draftSourceScope,
            )
            coroutineContext.ensureActive()
            val current = mutableState.value
            if (current.query.draft != requestState.query.draft ||
                current.query.draftSourceScope != requestState.query.draftSourceScope
            ) return@launch
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
                val initialization = coordinator.initializeRoute()
                appliedByMode.clear()
                appliedByMode.putAll(initialization.appliedByMode)
                val appliedHash = executionService.executionKeyFor(
                    initialization.query,
                    initialization.sourceScope,
                )
                mutableState.value = restoredStateFrom(initialization, appliedHash)
                val environmentChanged = latestEnvironmentSettings?.let { settings ->
                    reconcileEnvironment(settings, scheduleRetry = false)
                } == true
                val restoredQuery = restoreSavedDraft()
                val scroll = restoreScrollState()
                reduce(
                    SearchStateChange.RestorationCompleted(
                        restoredQuery = restoredQuery,
                        scrollState = scroll,
                    ),
                )
                savedStateHandle[SearchSavedStateKeys.RESTORATION_COMPLETED] = true
                persistDraftQuery()
                refreshTrending()
                if (environmentChanged) {
                    val current = mutableState.value
                    launchRootSearch(
                        kind = SearchRequestKind.RETRY,
                        query = current.query.applied,
                        sourceScope = current.query.appliedSourceScope,
                        persistAcceptedResult = false,
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

    private fun restoredStateFrom(
        initialization: SearchInitialization,
        appliedHash: String,
    ): SearchUiState {
        val base = SearchUiState(
            query = SearchQueryUiState(
                draft = initialization.query,
                applied = initialization.query,
                draftSourceScope = initialization.sourceScope,
                appliedSourceScope = initialization.sourceScope,
                appliedQueryHash = appliedHash,
                availableSources = initialization.availableSources,
                modeOptions = listOf(QueryMode.Unified) + initialization.availableSources.map(QueryMode::Source),
                enabledSourceCount = initialization.availableSources.size,
                supportedScopes = coordinator.supportedSearchScopes(initialization.query.mode),
                validationMessage = initialization.validationMessage,
            ),
            content = com.theoriacodex.app.search.state.SearchContentUiState(
                hasExecutedSearch = initialization.hasExecutedSearch,
            ),
            restoration = SearchRestorationUiState.Restoring,
        )
        val draft = SearchDraftReducer.restoreDraft(
            base,
            initialization.query,
            initialization.availableSources.toSet(),
            coordinator::supportedSearchScopes,
        ).state
        return draft.copy(
            query = draft.query.copy(
                applied = initialization.query,
                appliedSourceScope = initialization.sourceScope,
                appliedQueryHash = appliedHash,
            ),
            content = base.content,
            restoration = base.restoration,
        )
    }

    private fun restoreSavedDraft(): Boolean {
        val savedQuery = savedStateHandle.get<String>(SearchSavedStateKeys.DRAFT_QUERY)
            ?.let(SearchSavedQueryCodec::decode) ?: return false
        val reduction = SearchDraftReducer.restoreDraft(
            mutableState.value,
            savedQuery,
            mutableState.value.query.availableSources.toSet(),
            coordinator::supportedSearchScopes,
        )
        if (reduction.accepted) mutableState.value = reduction.state
        return reduction.accepted
    }

    private suspend fun restoreScrollState(): SearchScrollState? {
        return savedStateHandle.savedSearchScrollState() ?: coordinator.restoreSearchScrollState(
            queryHash = mutableState.value.query.appliedQueryHash,
            sourceScope = mutableState.value.query.appliedSourceScope,
        )
    }

    private fun persistScroll(action: SearchAction.ScrollChanged) {
        val index = action.firstVisibleItemIndex.coerceAtLeast(0)
        val offset = action.firstVisibleItemOffsetPx.coerceAtLeast(0)
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = index
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = offset
        val current = mutableState.value
        if (current.query.appliedSourceScope is com.theoriacodex.app.search.state.SearchSourceScope.Temporary) return
        val queryHash = current.query.appliedQueryHash.takeIf(String::isNotBlank) ?: return
        scrollPersistence.submit(
            SearchScrollPersistenceTarget(
                queryHash = queryHash,
                state = SearchScrollState(index, offset),
            ),
        )
    }

    private fun requestRouteEntryScrollRestoration() {
        if (mutableState.value.restoration !is SearchRestorationUiState.Restored) return
        val scrollState = savedStateHandle.savedSearchScrollState() ?: return
        reduce(SearchStateChange.RouteEntryScrollRestorationRequested(scrollState))
    }

    private fun persistDraftQuery() {
        val current = mutableState.value
        if (current.query.draftSourceScope is SearchSourceScope.Temporary) return
        savedStateHandle[SearchSavedStateKeys.DRAFT_QUERY] =
            SearchSavedQueryCodec.encode(current.query.draft)
    }

    private fun updateSuggestionInput(input: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            suggestions = current.suggestions.copy(
                input = input,
                canCommitInput = SearchDraftReducer.canCommitInput(current, input),
            ),
        )
    }

    private fun draftContext(): SearchDraftContext = SearchDraftContext(
        availableSources = mutableState.value.query.availableSources.toSet(),
        appliedByMode = appliedByMode,
    )

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

    private fun applyResolvedPosts(
        queryHash: String,
        posts: List<com.theoriacodex.domain.model.Post>,
    ) {
        val current = mutableState.value
        if (current.query.appliedQueryHash != queryHash) return
        val replacements = posts.associateBy { post -> post.id }
        if (replacements.isEmpty()) return
        var changed = false
        val updated = current.content.results.map { post ->
            replacements[post.id]?.also { replacement ->
                if (replacement != post) changed = true
            } ?: post
        }
        if (!changed) return
        mutableState.value = current.copy(
            content = current.content.copy(
                results = updated,
                displayVersion = current.content.displayVersion + 1,
            ),
        )
    }

    override fun onCleared() {
        effectChannel.close()
        super.onCleared()
    }

    companion object {
        fun factory(
            coordinator: SearchCoordinator, animatedDurationEnricher: AnimatedDurationEnricher,
        ): ViewModelProvider.Factory {
            return viewModelFactory {
                initializer {
                    SearchViewModel(
                        coordinator = coordinator,
                        savedStateHandle = createSavedStateHandle(),
                        animatedDurationEnricher = animatedDurationEnricher,
                    )
                }
            }
        }

        private const val DEFAULT_AUTOCOMPLETE_DELAY_MS = 300L
        private const val DEFAULT_SCROLL_PERSISTENCE_DELAY_MS = 150L
        private const val SEARCH_SCROLL_PERSISTENCE_KEY = "search-scroll-persistence"
    }
}

private fun SavedStateHandle.savedSearchScrollState(): SearchScrollState? {
    val index = get<Int>(SearchSavedStateKeys.SCROLL_INDEX) ?: return null
    val offset = get<Int>(SearchSavedStateKeys.SCROLL_OFFSET) ?: 0
    return SearchScrollState(index.coerceAtLeast(0), offset.coerceAtLeast(0))
}

private fun SearchSourceScope.reconciledWith(available: Set<SourceKey>): SearchSourceScope = when (this) {
    SearchSourceScope.GlobalUnified -> this
    is SearchSourceScope.Single -> SearchSourceScope.fromSources(listOf(source).filter { it in available })
    is SearchSourceScope.Temporary -> SearchSourceScope.fromSources(sources.filter { it in available })
}

private fun Query.reconciledWith(scope: SearchSourceScope): Query {
    val mode = when (scope) {
        SearchSourceScope.GlobalUnified, is SearchSourceScope.Temporary -> QueryMode.Unified
        is SearchSourceScope.Single -> QueryMode.Source(scope.source)
    }
    return if (mode == QueryMode.Unified) {
        copy(
            mode = mode,
            includeTerms = includeTerms.filter { it.isPortableGeneralTag },
            excludeTerms = excludeTerms.filter { it.isPortableGeneralTag },
        )
    } else {
        copy(mode = mode)
    }
}

internal data class SearchScrollPersistenceTarget(
    val queryHash: String,
    val state: SearchScrollState,
)

/**
 * Navigation-owner closeable for debounced scroll writes. Its scheduler is independent from
 * viewModelScope so ViewModel teardown can cancel route work first and still synchronously drain
 * the final pending position through the durable repository.
 */
internal class SearchScrollPersistenceController(
    dispatcher: CoroutineDispatcher,
    private val debounceMillis: Long,
    private val persist: suspend (SearchScrollPersistenceTarget) -> Unit,
) : AutoCloseable {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val commitMutex = Mutex()
    private var activeJob: Job? = null
    private var latest: SearchScrollPersistenceTarget? = null
    private var committed: SearchScrollPersistenceTarget? = null
    private var closed = false

    fun submit(target: SearchScrollPersistenceTarget) {
        synchronized(lock) {
            if (closed || target == latest) return
            latest = target
            activeJob?.cancel(CancellationException("Search scroll position superseded"))
            activeJob = scope.launch {
                if (debounceMillis > 0) delay(debounceMillis)
                commit(target)
            }
        }
    }

    /**
     * ViewModel.clear() has no suspending completion hook. The caller therefore waits for the one
     * final DataStore acknowledgement on Dispatchers.IO before the owned scheduler is cancelled.
     * This can delay teardown by the duration of that storage operation; imposing a timeout would
     * make the final position lossy again, so storage failure is surfaced instead of abandoned.
     */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeJob?.cancel(CancellationException("Search owner closed"))
        }
        try {
            runBlocking(Dispatchers.IO) {
                val pending = synchronized(lock) { latest?.takeIf { target -> target != committed } }
                if (pending != null) commit(pending)
            }
        } finally {
            scope.cancel(CancellationException("Search scroll scheduler closed"))
        }
    }

    private suspend fun commit(target: SearchScrollPersistenceTarget) {
        withContext(NonCancellable) {
            commitMutex.withLock {
                if (synchronized(lock) { committed == target }) return@withLock
                persist(target)
                synchronized(lock) { committed = target }
            }
        }
    }
}
