package com.theoriacodex.app.search

import kotlinx.coroutines.flow.emptyFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.search.state.withAcceptedSearchHistory
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchDraftContext
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.allowsScrollPersistence
import com.theoriacodex.app.search.state.withResolvedPosts
import com.theoriacodex.app.search.state.withSuggestionInput
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.modeKey
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    private val relatedPostsLoader: RelatedPostsLoading = UnsupportedRelatedPostsLoader,
    private val suggestionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val autocompleteDelayMs: Long = DEFAULT_AUTOCOMPLETE_DELAY_MS,
    private val rootRequestTimeoutMs: Long = DEFAULT_ROOT_REQUEST_TIMEOUT_MS,
    scrollPersistenceDelayMs: Long = DEFAULT_SCROLL_PERSISTENCE_DELAY_MS,
    scrollPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    reconnections: Flow<Unit> = emptyFlow(),
) : ViewModel(), RouteStateOwner<SearchUiState, SearchAction, SearchEffect> {
    private val mutableState = MutableStateFlow(initialSearchState(coordinator.availableSources, executionService))
    private val effectChannel = Channel<SearchEffect>(capacity = Channel.BUFFERED)
    private val relatedDiagnostics = com.theoriacodex.app.related.RelatedPostsDiagnostics("Search", this)
    private val relatedPosts = RelatedPostsRouteController(
        scope = viewModelScope,
        loader = relatedPostsLoader,
        currentState = { mutableState.value.relatedPosts },
        canonicalPosts = { mutableState.value.content.results },
        promotePost = { post, insertionIndex ->
            val current = mutableState.value
            mutableState.value = current.copy(
                content = current.content.copy(
                    results = com.theoriacodex.app.related.promoteRelatedPost(
                        current.content.results,
                        post,
                        insertionIndex,
                    ),
                    displayVersion = current.content.displayVersion + 1,
                ),
            )
        },
        updateState = { related ->
            relatedDiagnostics.record("state updated", related)
            mutableState.value = mutableState.value.copy(relatedPosts = related)
        },
        onClear = { state, reason -> relatedDiagnostics.record("clear: $reason", state) },
    )

    override val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    override val effects: Flow<SearchEffect> = effectChannel.receiveAsFlow()

    private var nextRequestId = 0L
    private var activeRequestJob: Job? = null
    private var restorationJob: Job? = null
    private var pendingRootSearch: (() -> Unit)? = null
    private var activeContinuation: SearchContinuation? = null
    private var pageRetrySource: SourceKey? = null

    /** Last run's first page is on screen awaiting its refresh, so partial results would only make it flicker. */
    private var showingCachedFirstPage = false
    private var scrolledCachedFirstPage = false
    private var latestEnvironmentSettings: AppSettings? = null
    private val appliedByMode = mutableMapOf<String, Query>()
    private val suggestionRefresh =
        SearchSuggestionRefresher(viewModelScope, mutableState, coordinator, suggestionDispatcher, autocompleteDelayMs)
    private val scrollPersistence = coordinator.scrollPersistence(scrollPersistenceDispatcher, scrollPersistenceDelayMs)
    init {
        relatedDiagnostics.record("owner created", mutableState.value.relatedPosts)
        addCloseable("related-posts-diagnostics", java.io.Closeable {
            relatedDiagnostics.record("owner cleared", mutableState.value.relatedPosts)
        })
        addCloseable(SEARCH_SCROLL_PERSISTENCE_KEY, scrollPersistence)
        viewModelScope.launch {
            reconnections.collect {
                retrySearchAfterReconnect(mutableState, ::retry) { source -> retrySource(source); activeRequestJob }
            }
        }
    }

    override fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.DraftEdit -> applyDraftAction(action)

            is SearchAction.SelectMode -> selectMode(action.mode)

            is SearchAction.ToggleTemporarySource -> toggleTemporarySource(action.source)

            is SearchAction.SelectSuggestionScope -> selectSuggestionScope(action)

            is SearchAction.AutocompleteChanged -> suggestionRefresh.refreshAutocomplete(action.input)

            SearchAction.RefreshAutocomplete -> suggestionRefresh.refreshAutocomplete(mutableState.value.suggestions.input)

            SearchAction.ClearAutocomplete -> {
                suggestionRefresh.cancelAutocomplete()
                clearSuggestionState()
            }

            SearchAction.UndoSearch -> mutableState.value.takeIf { it.canUndoSearch }?.searchHistory?.lastOrNull()?.let {
                applyHistoricalQuery(it.query, it.sourceScope, undo = true)
            }
            SearchAction.ApplyDraft -> applyDraft()

            is SearchAction.ApplyHistoricalQuery -> runAfterRestoration {
                applyHistoricalQuery(action.query, action.sourceScope)
            }

            is SearchAction.ApplyTagSearch -> runAfterRestoration { applyTagSearch(action) }

            is SearchAction.RetrySource -> retrySource(action.source)

            SearchAction.Retry -> retry()

            SearchAction.LoadNextPage -> loadNextPage()

            SearchAction.CancelActiveRequest -> cancelActiveRequest()

            SearchAction.DismissError -> dismissError()

            SearchAction.DismissValidation -> dismissValidation()

            SearchAction.Restore -> restore()

            SearchAction.Resume -> {
                requestRouteEntryScrollRestoration()
                resumeSearchIfNeeded()
            }

            is SearchAction.CommitTagInput -> commitTagInput(action.input)

            is SearchAction.RememberResolvedPost -> {
                mutableState.value = mutableState.value.withResolvedPosts(
                    mutableState.value.query.appliedQueryHash, listOf(action.post),
                )
            }

            is SearchAction.RelatedLikeCommitted -> relatedPosts.onLikeCommitted(action.post, action.outcome)
            is SearchAction.ShowRelatedPosts -> relatedPosts.show(action.post)

            SearchAction.RetryRelatedPosts -> relatedPosts.retry()

            SearchAction.DismissRelatedPosts -> relatedPosts.clear()

            SearchAction.ShowPreviousRelatedPosts -> relatedPosts.showPreviousPage()

            SearchAction.ShowNextRelatedPosts -> relatedPosts.showNextPage()

            is SearchAction.OpenRelatedResult -> openRelatedResult(action)

            is SearchAction.ScrollChanged -> persistScroll(action)

            is SearchAction.ScrollRestorationApplied -> reduce(
                SearchStateChange.ScrollRestorationApplied(action.requestId),
            )

            is SearchAction.OpenResult -> publishReduction(
                SearchStateReducer.reduce(mutableState.value, action),
            )
        }
    }

    private fun selectSuggestionScope(action: SearchAction.SelectSuggestionScope) {
        val reduction = SearchDraftReducer.selectScope(mutableState.value, action.scope)
        if (reduction.accepted) {
            mutableState.value = reduction.state
            suggestionRefresh.refreshAutocomplete(mutableState.value.suggestions.input)
        }
    }

    private fun dismissError() {
        mutableState.value = mutableState.value.copy(
            content = mutableState.value.content.copy(error = null),
        )
    }

    private fun dismissValidation() {
        mutableState.value = mutableState.value.copy(
            query = mutableState.value.query.copy(validationMessage = null),
        )
    }

    private fun applyDraftAction(action: SearchAction.DraftEdit) {
        mutateDraft {
            when (action) {
                is SearchAction.SelectSort -> SearchDraftReducer.mutateQuery(it) { query -> query.copy(sort = action.sort) }.state
                is SearchAction.SetDateRange -> SearchDraftReducer.mutateQuery(it) { query -> query.copy(dateRange = action.range) }.state
                is SearchAction.SetDateRangePreset -> {
                    val range = action.preset.rangeEndingAt(System.currentTimeMillis())
                    SearchDraftReducer.mutateQuery(it) { query -> query.copy(dateRange = range) }.state
                }
                is SearchAction.AddIncludeTerm -> SearchDraftReducer.addTerm(it, action.term, excluded = false).state
                is SearchAction.AddExcludeTerm -> SearchDraftReducer.addTerm(it, action.term, excluded = true).state
                is SearchAction.RemoveIncludeTerm -> SearchDraftReducer.removeTerm(it, action.term, excluded = false).state
                is SearchAction.RemoveExcludeTerm -> SearchDraftReducer.removeTerm(it, action.term, excluded = true).state
                is SearchAction.AddIncludeAlternative -> SearchDraftReducer.addIncludeAlternative(it, action.groupIndex, action.term).state
                is SearchAction.SplitIncludeAlternative -> SearchDraftReducer.splitIncludeAlternative(it, action.groupIndex, action.term).state
                is SearchAction.RemoveIncludeGroup -> SearchDraftReducer.removeIncludeGroup(it, action.groupIndex).state
                is SearchAction.AddSavedTagGroup -> SearchDraftReducer.addIncludeGroup(it, action.terms).state
                is SearchAction.RestoreDraftTerms -> SearchDraftReducer.restoreTerms(it, action.includeTermGroups, action.excludeTerms).state
                is SearchAction.IncludeSuggestion -> SearchDraftReducer.addTerm(it, action.suggestion.toSearchTerm(), excluded = false).state
                is SearchAction.ExcludeSuggestion -> SearchDraftReducer.addTerm(it, action.suggestion.toSearchTerm(), excluded = true).state
                SearchAction.ResetDraft -> SearchDraftReducer.resetDraft(it).state
                SearchAction.ClearDraft -> SearchDraftReducer.clearDraft(it).state
                is SearchAction.AddPostIncludeTerm -> {
                    SearchDraftReducer.addPostTerm(
                        it, action.post, action.term, excluded = false,
                        availableSources = it.query.availableSources.toSet(),
                        supportedScopes = coordinator::supportedSearchScopes,
                    ).state
                }
                is SearchAction.AddPostExcludeTerm -> {
                    SearchDraftReducer.addPostTerm(
                        it, action.post, action.term, excluded = true,
                        availableSources = it.query.availableSources.toSet(),
                        supportedScopes = coordinator::supportedSearchScopes,
                    ).state
                }
                is SearchAction.AddPostIncludeAlternative ->
                    SearchDraftReducer.addKnownIncludeAlternative(it, action.post.id.source, action.groupIndex, action.term).state
                is SearchAction.AddSavedTagAlternative ->
                    SearchDraftReducer.addKnownIncludeAlternative(it, action.source, action.groupIndex, action.term).state
                is SearchAction.SetNhentaiLanguage -> SearchDraftReducer.setNhentaiLanguage(it, action.filter).state
                is SearchAction.SetNhentaiFullColor -> SearchDraftReducer.setNhentaiFullColor(it, action.enabled).state
                SearchAction.ResetFilters -> {
                    SearchDraftReducer.mutateQuery(it) { query ->
                        query.copy(sort = com.theoriacodex.domain.model.SortMode.NEWEST, dateRange = null)
                    }.state
                }
            }
        }
    }

    /**
     * Switches the draft to [mode]'s last search. Once results are on screen, that search also runs,
     * so the source, terms, filters, and results always describe the same search.
     */
    private fun selectMode(mode: QueryMode) {
        suggestionRefresh.cancelAutocomplete()
        val current = SearchDraftReducer.selectMode(
            state = mutableState.value,
            mode = mode,
            context = draftContext(),
            supportedScopes = coordinator::supportedSearchScopes,
        ).state
        mutableState.value = current
        persistDraftQuery()
        suggestionRefresh.refreshTrending()
        if (current.content.hasExecutedSearch && current.hasPendingChanges) {
            launchRootSearch(
                kind = SearchRequestKind.REPLACE,
                query = current.query.draft,
                sourceScope = current.query.draftSourceScope,
                persistAcceptedResult = true,
            )
        }
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
        suggestionRefresh.cancelAutocomplete()
        mutableState.value = reduction.state.copy(
            suggestions = reduction.state.suggestions.copy(
                input = "",
                canCommitInput = false,
            ),
        )
        persistDraftQuery()
        if (input.isBlank()) suggestionRefresh.refreshTrending()
        suggestionRefresh.refreshAutocomplete(input)
    }

    /** Runs the draft. A typed number not yet added as a tag is added first, so a lone nhentai ID opens its gallery. */
    private fun applyDraft() {
        val pending = mutableState.value.suggestions.input.trim()
        if (pending.isNotEmpty() && pending.all(Char::isDigit) && SearchDraftReducer.canCommitInput(mutableState.value, pending)) {
            val draftBefore = mutableState.value.query.draft
            commitTagInput(pending)
            if (mutableState.value.query.draft == draftBefore) return // opened as a gallery
        }
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
        )
    }

    private fun applyTagSearch(action: SearchAction.ApplyTagSearch) {
        val reduction = SearchDraftReducer.prepareTagSearch(
            state = mutableState.value,
            includeTermGroups = action.includeTermGroups,
            excludeTerms = action.excludeTerms,
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

    /**
     * Runs a search requested from outside Search, such as from Recents or a Codex. Before Search has
     * restored, it waits (latest request wins), because restoration would replace its draft and request.
     */
    private fun runAfterRestoration(search: () -> Unit) {
        if (mutableState.value.restoration is SearchRestorationUiState.Restored) {
            search()
            return
        }
        pendingRootSearch = search
        restore()
    }

    private fun applyHistoricalQuery(query: Query, sourceScope: SearchSourceScope, undo: Boolean = false) {
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
        val restoredScope = sourceScope.reconciledWith(mutableState.value.query.availableSources.toSet())
        if (sourceScope is SearchSourceScope.Temporary && restoredScope !is SearchSourceScope.Temporary) {
            effectChannel.trySend(SearchEffect.ShowMessage("Multi-Search sources are unavailable"))
            return
        }
        suggestionRefresh.cancelAutocomplete()
        mutableState.value = reduction.state.copy(
            query = reduction.state.query.copy(draftSourceScope = restoredScope),
        )
        persistDraftQuery()
        val current = mutableState.value
        launchRootSearch(
            kind = SearchRequestKind.REPLACE,
            query = current.query.draft,
            sourceScope = current.query.draftSourceScope,
            persistAcceptedResult = true,
            undo = undo,
            undoScrollState = if (undo) current.searchHistory.lastOrNull()?.scrollState else null,
        )
    }

    private fun loadNextPage() {
        if (!mutableState.value.content.canLoadMore) return
        val continuation = activeContinuation ?: return
        launchPage(continuation)
    }

    private fun openRelatedResult(action: SearchAction.OpenRelatedResult) {
        if (action.index !in action.visibleResults.indices) return
        val request = mutableState.value.relatedPosts.requestOrNull ?: return
        effectChannel.trySend(
            SearchEffect.OpenViewer(
                posts = action.visibleResults.toList(),
                context = ViewerLaunchContext(
                    queryHash = "related:${request.seed.id.source.id}:${request.seed.id.sourcePostId}",
                    startIndex = action.index,
                    streamSource = ViewerStreamSource.RELATED,
                    scrollOffsetHint = 0,
                ),
                liveSearchBinding = false,
            )
        )
    }

    fun synchronizeEnvironment(settings: AppSettings) {
        val previous = latestEnvironmentSettings
        latestEnvironmentSettings = settings
        if (!coordinator.isInitialized) return
        val selectedSources = mutableState.value.query.draftSources
        val weightsChanged = previous != null && selectedSources.size > 1 &&
            com.theoriacodex.data.repository.SourceWeightSettings.weightsFor(previous.runtime, selectedSources) !=
            com.theoriacodex.data.repository.SourceWeightSettings.weightsFor(settings.runtime, selectedSources)
        reconcileEnvironment(settings, scheduleRetry = !weightsChanged)
        // Returning from Settings applies the group whose weights were edited, including a pending selection.
        if (weightsChanged) applyDraft()
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
                unifiedSources = settings.runtime.enabledSources.intersect(available),
                sourceTermLimits = change.sourceTermLimits,
                supportedScopes = scopes,
                selectedScope = current.query.selectedScope.takeIf { it in scopes }
                    ?: com.theoriacodex.domain.adapter.FacetedSearchScope.All,
            ),
        )
        persistDraftQuery()
        val requiresRetry = hadExecutedSearch && (change.settingsChanged || change.sourcesChanged ||
            draft != current.query.draft || applied != current.query.applied)
        if (requiresRetry && scheduleRetry) {
            val latest = mutableState.value
            launchRootSearch(
                kind = SearchRequestKind.RETRY,
                query = latest.query.applied,
                sourceScope = latest.query.appliedSourceScope,
                persistAcceptedResult = false,
                relatedResetReason = "environment: runtime=${change.settingsChanged} sources=${change.sourcesChanged} " +
                    "draft=${draft != current.query.draft} applied=${applied != current.query.applied}",
            )
        }
        return requiresRetry
    }

    private fun mutateDraft(mutation: (SearchUiState) -> SearchUiState) {
        mutableState.value = mutation(mutableState.value)
        persistDraftQuery()
    }

    /**
     * Adds a typed tag to the draft. A bare number that would be the draft's only tag is an nhentai
     * gallery ID: it opens the Viewer instead, and the draft stays as it was.
     */
    private fun commitTagInput(input: String) {
        val reduction = SearchDraftReducer.commitInput(mutableState.value, input)
        if (!reduction.accepted) {
            mutableState.value = reduction.state
            updateSuggestionInput(input)
            return
        }
        val directGalleryId = SearchDraftReducer.directNhentaiGalleryIdCandidate(reduction.state.query.draft)
            ?.takeIf { input.trim().all(Char::isDigit) }
        if (directGalleryId != null) {
            clearSuggestionState()
            viewModelScope.launch { openDirectNhentaiGallery(directGalleryId) }
            return
        }
        mutableState.value = reduction.state
        clearSuggestionState()
        persistDraftQuery()
    }

    private suspend fun openDirectNhentaiGallery(galleryId: String) {
        effectChannel.send(coordinator.directNhentaiGalleryEffect(galleryId, mutableState.value.content.results))
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
            paintCachedFirstPage = true,
        )
    }

    private fun launchRootSearch(
        kind: SearchRequestKind,
        query: Query,
        sourceScope: com.theoriacodex.app.search.state.SearchSourceScope,
        persistAcceptedResult: Boolean,
        undo: Boolean = false,
        undoScrollState: SearchScrollState? = null,
        onSuccess: suspend () -> Unit = {},
        relatedResetReason: String = "root search ($kind)",
        paintCachedFirstPage: Boolean = false,
    ) {
        relatedPosts.clear(relatedResetReason)
        cancelActiveRequest()
        activeContinuation = null
        pageRetrySource = null
        showingCachedFirstPage = false
        scrolledCachedFirstPage = false
        val expectedExecutionKey = executionService.executionKeyFor(query, sourceScope)
        val requestId = ++nextRequestId
        reduce(SearchStateChange.BeginRequest(requestId, kind, query))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (paintCachedFirstPage) paintCachedFirstPage(requestId, query, sourceScope, expectedExecutionKey)
                executeRootRequest(
                    requestId, query, sourceScope, expectedExecutionKey, persistAcceptedResult, undo, undoScrollState, onSuccess,
                )
            } catch (_: TimeoutCancellationException) {
                failRootRequestIfCurrent(requestId, ROOT_REQUEST_TIMEOUT_MESSAGE, query, sourceScope, expectedExecutionKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failRootRequestIfCurrent(requestId, error.message ?: "Search failed", query, sourceScope, expectedExecutionKey)
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

    private suspend fun executeRootRequest(
        requestId: Long, query: Query, sourceScope: SearchSourceScope,
        expectedExecutionKey: String, persistAcceptedResult: Boolean, undo: Boolean,
        undoScrollState: SearchScrollState?, onSuccess: suspend () -> Unit,
    ) = withTimeout(rootRequestTimeoutMs) {
        val publisher = RootResultPublisher(requestId, expectedExecutionKey, persistAcceptedResult, undo, undoScrollState)
        val result = executionService.executeInitialProgressively(query, sourceScope) { publisher.publishPartial(it) }
        coroutineContext.ensureActive()
        if (!isAdmittedResult(requestId, result, expectedExecutionKey)) {
            activeContinuation = null
            cancelRequestIfCurrent(requestId)
            return@withTimeout
        }
        val succeeded = publisher.publish(result) { completeRequest(requestId, result, expectedExecutionKey) } ?: return@withTimeout
        rememberFirstPage(result)
        persistDraftQuery()
        if (persistAcceptedResult) withContext(NonCancellable) {
            executionService.recordAcceptedSearch(result)
        }
        if (succeeded) onSuccess()
    }

    /** Puts one root request's results on screen; persisting it and resetting the scroll target happen only once. */
    private inner class RootResultPublisher(
        private val requestId: Long,
        private val expectedExecutionKey: String,
        persistAcceptedResult: Boolean,
        private val undo: Boolean,
        private val undoScrollState: SearchScrollState?,
    ) {
        private var acceptancePending = persistAcceptedResult

        suspend fun publishPartial(partial: SearchExecutionResult.Success) {
            if (!showingCachedFirstPage && isAdmittedResult(requestId, partial, expectedExecutionKey)) {
                publish(partial) { showPartialResults(requestId, partial) }
            }
        }

        /** Returns what [show] returned, or null when this request no longer owns the screen. */
        suspend fun publish(result: SearchExecutionResult, show: () -> Boolean): Boolean? {
            val accepting = acceptancePending
            if (accepting) scrollPersistence.reset(result.executionKey) {
                executionService.persistAppliedSearch(result.query, result.sourceScope, result.executionKey)
            }
            if (!isAdmittedResult(requestId, result, expectedExecutionKey)) return null
            val previous = mutableState.value
            val shown = show()
            acceptancePending = false
            if (accepting) rememberAcceptedSearchNavigation(previous, undo, undoScrollState)
            return shown
        }
    }

    private fun rememberFirstPage(result: SearchExecutionResult) {
        if (result is SearchExecutionResult.Success && result.posts.isNotEmpty()) {
            viewModelScope.launch { executionService.rememberFirstPage(result.executionKey, result.posts) }
        }
    }

    /** Preserve Undo history and the accepted search's route scroll request together. */
    private fun rememberAcceptedSearchNavigation(previous: SearchUiState, undo: Boolean, undoScrollState: SearchScrollState?) {
        mutableState.value = mutableState.value.withAcceptedSearchHistory(
            previous, undo, savedStateHandle.savedSearchScrollState() ?: SearchScrollState(0, 0),
        )
        val scrollState = undoScrollState ?: SearchScrollState(0, 0)
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = scrollState.firstVisibleItemIndex
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = scrollState.firstVisibleItemOffsetPx
        reduce(
            if (undoScrollState != null) SearchStateChange.RouteEntryScrollRestorationRequested(scrollState)
            else SearchStateChange.ScrollResetRequested,
        )
    }

    private suspend fun paintCachedFirstPage(
        requestId: Long,
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
    ) {
        val posts = executionService.cachedFirstPage(executionKey, sourceScope)
        if (posts.isEmpty() || mutableState.value.execution.activeRequestId != requestId) return
        showingCachedFirstPage = true
        reduce(SearchStateChange.PublishPartialResults(requestId, query, posts, emptyList(), sourceScope, executionKey))
    }

    /** Someone who has scrolled the cached page keeps their place: its posts stay put and new ones go below. */
    private fun refreshedResults(fresh: List<Post>): List<Post> {
        if (!showingCachedFirstPage || !scrolledCachedFirstPage) return fresh
        val freshById = fresh.associateBy(Post::id)
        val shown = mutableState.value.content.results.map { post -> freshById[post.id] ?: post }
        return (shown + fresh).distinctBy(Post::id)
    }

    private fun showPartialResults(requestId: Long, result: SearchExecutionResult.Success): Boolean {
        rememberAppliedQuery(result.query, result.sourceScope)
        reduce(
            SearchStateChange.PublishPartialResults(
                requestId, result.query, result.posts, result.statuses, result.sourceScope, result.executionKey,
            ),
        )
        return true
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
                activeContinuation = result.continuation
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
                        results = refreshedResults(result.posts),
                        statuses = result.statuses,
                        canLoadMore = result.continuation.canLoadMore,
                    ),
                )
                showingCachedFirstPage = false
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

    private fun retry() {
        val current = mutableState.value
        if (current.content.error?.requestKind == SearchRequestKind.PAGE) {
            launchPage(activeContinuation, pageRetrySource)
        } else {
            launchRootSearch(SearchRequestKind.RETRY, current.query.applied,
                current.query.appliedSourceScope, persistAcceptedResult = false)
        }
    }

    private fun retrySource(source: SourceKey) {
        val current = mutableState.value
        if (current.execution.activeRequestId != null || current.content.statuses.none {
            it.source == source && it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
        }) return
        launchPage(activeContinuation, source)
    }

    private fun launchPage(continuation: SearchContinuation?, retrySource: SourceKey? = null) {
        pageRetrySource = retrySource
        val current = mutableState.value
        val query = continuation?.query ?: current.query.applied
        val executionKey = continuation?.executionKey ?: current.query.appliedQueryHash
        cancelActiveRequest()
        val requestId = ++nextRequestId
        reduce(SearchStateChange.BeginRequest(requestId, SearchRequestKind.PAGE, query))
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = if (retrySource == null) executionService.executePage(requireNotNull(continuation))
                else coordinator.retrySource(query, current.query.appliedSourceScope, continuation, retrySource)
                coroutineContext.ensureActive()
                if (!mutableState.value.admitsPage(requestId, result, executionKey)) {
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

    private fun completePageRequest(requestId: Long, result: SearchPageResult) {
        when (result) {
            is SearchPageResult.Failure -> reduce(
                SearchStateChange.RequestFailed(requestId, result.message, result.statuses),
            )
            is SearchPageResult.Success -> {
                pageRetrySource = null
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
        requestId?.let { reduce(SearchStateChange.RequestCancelled(it)) }
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

    /** Records the attempted query as a provider failure does, so Retry repeats it and not the search before it. */
    private fun failRootRequestIfCurrent(
        requestId: Long,
        message: String,
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
    ) {
        if (mutableState.value.execution.activeRequestId != requestId) return
        rememberAppliedQuery(query, sourceScope)
        reduce(SearchStateChange.RequestFailed(requestId, message, appliedQuery = query,
            appliedSourceScope = sourceScope, appliedQueryHash = executionKey))
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
                mutableState.value = SearchStateReducer.restore(
                    initialization, appliedHash, coordinator::supportedSearchScopes,
                )
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
                suggestionRefresh.refreshTrending()
                val pendingSearch = pendingRootSearch.also { pendingRootSearch = null }
                if (pendingSearch != null) {
                    pendingSearch()
                } else if (environmentChanged) {
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
        if (showingCachedFirstPage && action.firstVisibleItemIndex > 0) scrolledCachedFirstPage = true
        if (!mutableState.value.restoration.allowsScrollPersistence) return
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
        reduce(SearchStateChange.RouteEntryScrollRestorationRequested(scrollState, keepLiveGrid = true))
    }

    private fun persistDraftQuery() {
        val current = mutableState.value
        if (current.query.draftSourceScope is SearchSourceScope.Temporary) return
        savedStateHandle[SearchSavedStateKeys.DRAFT_QUERY] =
            SearchSavedQueryCodec.encode(current.query.draft)
    }
    private fun updateSuggestionInput(input: String) {
        mutableState.value = mutableState.value.withSuggestionInput(input)
    }
    private fun draftContext(): SearchDraftContext = SearchDraftContext(
        availableSources = mutableState.value.query.availableSources.toSet(),
        appliedByMode = appliedByMode,
    )

    private fun clearSuggestionState() {
        suggestionRefresh.cancelAutocomplete()
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
                if (effect.context.streamSource != ViewerStreamSource.RELATED) {
                    coordinator.setViewerLaunchContext(effect.context)
                }
                effectChannel.send(effect)
            }

            is SearchEffect.ShowMessage -> effectChannel.trySend(effect)
        }
    }

    override fun onCleared() {
        effectChannel.close()
    }

    private companion object {
        const val DEFAULT_AUTOCOMPLETE_DELAY_MS = 300L
        const val DEFAULT_ROOT_REQUEST_TIMEOUT_MS = 60_000L
        const val DEFAULT_SCROLL_PERSISTENCE_DELAY_MS = 150L
        const val SEARCH_SCROLL_PERSISTENCE_KEY = "search-scroll-persistence"
        const val ROOT_REQUEST_TIMEOUT_MESSAGE = "Search timed out. Check your connection and try again."
    }
}
