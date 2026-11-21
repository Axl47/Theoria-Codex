package com.theoriacodex.app.search

import kotlinx.coroutines.flow.emptyFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.search.state.appliedHistoryEntry
import com.theoriacodex.app.search.state.CachedFirstPage
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchDraftReducer
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRequest
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchStateChange
import com.theoriacodex.app.search.state.SearchStateReducer
import com.theoriacodex.app.search.state.SearchSuggestionsUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.reconciledWith
import com.theoriacodex.app.search.state.allowsScrollPersistence
import com.theoriacodex.app.search.state.openScrollRequestId
import com.theoriacodex.app.search.state.withResolvedPosts
import com.theoriacodex.app.search.state.withSuggestionInput
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsRouteController
import com.theoriacodex.app.related.UnsupportedRelatedPostsLoader
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.related.relatedViewerContext
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
class SearchViewModel(
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
    liveSearchDelays: LiveSearchDelays = LiveSearchDelays(),
) : ViewModel(), RouteStateOwner<SearchUiState, SearchAction, SearchEffect>, ViewerFeedOwner {
    private val mutableState = MutableStateFlow(initialSearchState(coordinator.availableSources))
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
    override val streamSource = ViewerStreamSource.SEARCH
    override val viewerFeed: ViewerFeedSnapshot get() = mutableState.value.viewerFeed()
    override val viewerFeedChanges: Flow<ViewerFeedSnapshot> = mutableState.map { it.viewerFeed() }.distinctUntilChanged()
    override val related: RelatedPostsRouteController get() = relatedPosts

    private var nextRequestId = 0L
    private var activeRequestJob: Job? = null
    private var restorationJob: Job? = null
    private var pendingRootSearch: (() -> Unit)? = null
    /** A draft edit made over Search waits to run until Search is shown again. */
    private var draftStaged = false
    /** A first search was cancelled before it answered; its replacement stays scheduled across edits until one runs. */
    private var firstSearchSuperseded = false
    private var latestEnvironmentSettings: AppSettings? = null
    /** The source settings the state was last reconciled with; null until Search has restored. */
    private var runtime: SourceRuntimeSettings? = null
    private val suggestionRefresh =
        SearchSuggestionRefresher(viewModelScope, mutableState, coordinator, suggestionDispatcher, autocompleteDelayMs)
    private val scrollPersistence = coordinator.scrollPersistence(scrollPersistenceDispatcher, scrollPersistenceDelayMs)
    private val liveSearch = LiveSearchScheduler(
        viewModelScope, liveSearchDelays,
        onWaitingChanged = { waiting ->
            val current = mutableState.value
            mutableState.value = current.copy(execution = current.execution.copy(liveSearchWaiting = waiting))
        },
    ) { runDraft(liveKind(), SearchAcceptance.Live) }
    private val settlement = SearchSettlement(mutableState, viewModelScope) { executionService.recordAcceptedSearch(it) }
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
            SearchAction.ClearDraft -> {
                suggestionRefresh.cancelAutocomplete()
                applyDraftAction(SearchAction.ClearDraft)
            }

            is SearchAction.DraftEdit -> applyDraftAction(action)

            is SearchAction.StageDraftEdit -> stageDraftEdit(action.edit)

            is SearchAction.SelectMode -> selectMode(action.mode)

            is SearchAction.ToggleTemporarySource -> toggleTemporarySource(action.source)

            is SearchAction.SelectSuggestionScope -> selectSuggestionScope(action)

            is SearchAction.AutocompleteChanged -> suggestionRefresh.refreshAutocomplete(action.input)

            SearchAction.RefreshAutocomplete -> suggestionRefresh.refreshAutocomplete(mutableState.value.suggestions.input)

            SearchAction.ClearAutocomplete -> clearSuggestionState()

            SearchAction.UndoSearch -> stepHistory(back = true)

            SearchAction.RedoSearch -> stepHistory(back = false)

            SearchAction.ApplyDraft -> {
                // Live search may already run, or show, this exact draft: asking again only settles it.
                if (mutableState.value.showsDraft) settlement.settle() else applyDraft()
                clearSuggestionState()
            }

            SearchAction.SettleSearch -> if (liveSearch.cancel()) applyDraft() else settlement.settle()

            is SearchAction.ApplyHistoricalQuery -> runAfterRestoration {
                applyHistoricalQuery(action.query, action.sourceScope)
            }

            is SearchAction.ApplyTagSearch -> runAfterRestoration { applyTagSearch(action) }

            is SearchAction.RetrySource -> retrySource(action.source)

            SearchAction.Retry -> retry()

            SearchAction.LoadNextPage -> loadNextPage()

            SearchAction.CancelActiveRequest -> cancelActiveRequest()


            SearchAction.Restore -> restore()

            SearchAction.Resume -> {
                requestRouteEntryScrollRestoration()
                if (draftStaged) {
                    draftStaged = false
                    // Staged before Search first opened, the edit runs on the restored draft, not the blank one.
                    runAfterRestoration(::scheduleLiveSearch)
                }
                resumeSearchIfNeeded()
            }

            is SearchAction.CommitTagInput -> commitTagInput(action.input)

            is SearchAction.RememberResolvedPost -> {
                mutableState.value = mutableState.value.withResolvedPosts(
                    mutableState.value.query.appliedQueryHash, listOf(action.post),
                )
            }

            is SearchAction.OpenRelatedResult -> {
                settlement.settle()
                openRelatedResult(action)
            }

            is SearchAction.ScrollChanged -> persistScroll(action)

            is SearchAction.ScrollRestorationApplied -> reduce(
                SearchStateChange.ScrollRestorationApplied(action.requestId),
            )

            is SearchAction.OpenResult -> {
                settlement.settle()
                publishReduction(SearchStateReducer.reduce(mutableState.value, action))
            }
        }
    }

    private fun selectSuggestionScope(action: SearchAction.SelectSuggestionScope) {
        val reduction = SearchDraftReducer.selectScope(mutableState.value, action.scope)
        if (reduction.accepted) {
            mutableState.value = reduction.state
            suggestionRefresh.refreshAutocomplete(mutableState.value.suggestions.input)
        }
    }

    /** Steps back or forward through settled searches, returning to each one's place. */
    private fun stepHistory(back: Boolean) {
        clearSuggestionState()
        val current = mutableState.value
        val onScreen = current.appliedHistoryEntry(savedStateHandle.savedSearchScrollState() ?: SearchScrollState(0, 0))
        val step = (if (back) current.history.stepBack(onScreen) else current.history.stepForward(onScreen)) ?: return
        if (!restoreDraft(step.target.query, step.target.sourceScope)) return
        mutableState.value = mutableState.value.copy(history = step.history)
        runDraft(acceptance = SearchAcceptance.Step(step.target.scrollState))
    }

    private fun applyDraftAction(action: SearchAction.DraftEdit) {
        mutateDraft { SearchDraftReducer.edit(it, action, System.currentTimeMillis()) }
    }

    /** Edits the draft from over Search without running it behind that screen; [SearchAction.Resume] runs it. */
    private fun stageDraftEdit(edit: SearchAction.DraftEdit) {
        liveSearch.cancel()
        mutableState.value = SearchDraftReducer.edit(mutableState.value, edit, System.currentTimeMillis())
        persistDraftQuery()
        draftStaged = true
    }

    /**
     * Switches the draft to [mode]'s last search. Once a search is on screen, waiting or running, that search also
     * runs live, so the source, terms, filters, and results always describe the same search.
     */
    private fun selectMode(mode: QueryMode) {
        suggestionRefresh.cancelAutocomplete()
        val current = SearchDraftReducer.selectMode(mutableState.value, mode).state
        mutableState.value = current
        persistDraftQuery()
        suggestionRefresh.refreshTrending()
        // Before any search has been asked for, picking a source only prepares the draft.
        if (current.hasCommittedSearch) scheduleLiveSearch()
    }

    private fun toggleTemporarySource(source: SourceKey) {
        val input = mutableState.value.suggestions.input
        val reduction = SearchDraftReducer.toggleTemporarySource(mutableState.value, source)
        if (!reduction.accepted) return
        suggestionRefresh.cancelAutocomplete()
        mutableState.value = reduction.state
        persistDraftQuery()
        if (input.isBlank()) suggestionRefresh.refreshTrending()
        suggestionRefresh.refreshAutocomplete(input)
        if (reduction.state.hasCommittedSearch) scheduleLiveSearch()
    }

    /** The root request in flight asks for exactly the draft. */
    private val SearchUiState.runsDraft: Boolean
        get() = execution.request?.takeUnless { it.isPage }?.let { request ->
            execution.submittedQuery == query.draft && request.sourceScope == query.draftSourceScope
        } == true

    /** The draft is running, or its results are on screen, and nothing typed is waiting to join it. */
    private val SearchUiState.showsDraft: Boolean
        get() = suggestions.input.isBlank() && (
            runsDraft ||
                execution.request?.isPage != false && content.hasExecutedSearch && !hasPendingChanges && content.error == null
        )

    /** A search is on screen, waiting out its live pause or running, so a source change must reschedule it. */
    private val SearchUiState.hasCommittedSearch: Boolean
        get() = content.hasExecutedSearch || execution.liveSearchWaiting || execution.request?.isPage == false

    /** Runs the draft. A typed number not yet added as a tag is added first, so a lone nhentai ID opens its gallery. */
    private fun applyDraft() {
        val pending = mutableState.value.suggestions.input.trim()
        if (pending.isNotEmpty() && pending.all(Char::isDigit) && SearchDraftReducer.canCommitInput(mutableState.value, pending)) {
            val draftBefore = mutableState.value.query.draft
            commitTagInput(pending)
            if (mutableState.value.query.draft == draftBefore) return // opened as a gallery
        }
        runDraft(liveKind())
    }

    private fun liveKind() =
        if (mutableState.value.content.hasExecutedSearch) SearchRequestKind.REPLACE else SearchRequestKind.INITIAL

    /**
     * The draft changed: the request in flight is now out of date, and the draft runs after a short pause unless it
     * is back to the search on screen. An empty draft is runnable like any other, so it replaces a cancelled first search.
     */
    private fun scheduleLiveSearch() {
        val current = mutableState.value
        val request = current.execution.request
        val runsDraft = current.runsDraft
        val cancelsRequest = request != null && !request.isPage && !runsDraft
        if (cancelsRequest) cancelActiveRequest()
        // Before any search has run, the applied search is only a placeholder, so a draft equal to it still
        // needs its own run when the first search it superseded is cancelled, including by an earlier edit
        // whose replacement is still waiting; otherwise nothing would answer.
        val supersedesFirstSearch = !current.content.hasExecutedSearch && (cancelsRequest || firstSearchSuperseded)
        if ((current.hasPendingChanges || supersedesFirstSearch) && !runsDraft) {
            firstSearchSuperseded = supersedesFirstSearch
            liveSearch.schedule(singleSource = current.query.draftSourceScope is SearchSourceScope.Single)
        } else {
            firstSearchSuperseded = false
            liveSearch.cancel()
        }
    }

    private fun applyTagSearch(action: SearchAction.ApplyTagSearch) {
        val reduction = SearchDraftReducer.prepareTagSearch(
            state = mutableState.value,
            includeTermGroups = action.includeTermGroups,
            excludeTerms = action.excludeTerms,
            mode = action.mode,
        )
        if (!reduction.accepted) {
            effectChannel.trySend(SearchEffect.ShowMessage("Search source is unavailable"))
            return
        }
        mutableState.value = reduction.state
        persistDraftQuery()
        runDraft()
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

    private fun applyHistoricalQuery(query: Query, sourceScope: SearchSourceScope) {
        if (restoreDraft(query, sourceScope)) runDraft()
    }

    /** Makes [query] in [sourceScope] the draft; false, with a message, when it can no longer run here. */
    private fun restoreDraft(query: Query, sourceScope: SearchSourceScope): Boolean {
        val reduction = SearchDraftReducer.restoreDraft(mutableState.value, query)
        if (!reduction.accepted) {
            effectChannel.trySend(SearchEffect.ShowMessage("Search source is unavailable"))
            return false
        }
        val restoredScope = sourceScope.reconciledWith(mutableState.value.query.availableSources.toSet())
        if (sourceScope is SearchSourceScope.Temporary && restoredScope !is SearchSourceScope.Temporary) {
            effectChannel.trySend(SearchEffect.ShowMessage("Multi-Search sources are unavailable"))
            return false
        }
        suggestionRefresh.cancelAutocomplete()
        mutableState.value = reduction.state.copy(
            query = reduction.state.query.copy(draftSourceScope = restoredScope),
        )
        persistDraftQuery()
        return true
    }

    override fun loadNextPage() {
        val current = mutableState.value
        if (current.content.canLoadMore && current.execution.continuation != null) launchPage()
    }

    override fun rememberResolvedPost(post: Post) = onAction(SearchAction.RememberResolvedPost(post))

    private fun openRelatedResult(action: SearchAction.OpenRelatedResult) {
        if (action.index !in action.visibleResults.indices) return
        val request = mutableState.value.relatedPosts.requestOrNull ?: return
        effectChannel.trySend(
            SearchEffect.OpenViewer(
                posts = action.visibleResults.toList(),
                context = relatedViewerContext(request.seed, action.index),
                liveSearchBinding = false,
            )
        )
        // Like opening a result, this takes over the grid: a pending restore must not jump it on return.
        mutableState.value.restoration.openScrollRequestId?.let { requestId ->
            reduce(SearchStateChange.ScrollRestorationApplied(requestId))
        }
    }

    fun synchronizeEnvironment(settings: AppSettings) {
        val previous = latestEnvironmentSettings
        latestEnvironmentSettings = settings
        if (runtime == null) return
        val selectedSources = mutableState.value.query.draftSources
        val weightsChanged = previous != null && selectedSources.size > 1 &&
            com.theoriacodex.data.repository.SourceWeightSettings.weightsFor(previous.runtime, selectedSources) !=
            com.theoriacodex.data.repository.SourceWeightSettings.weightsFor(settings.runtime, selectedSources)
        reconcileEnvironment(settings, scheduleRetry = !weightsChanged)
        if (weightsChanged) scheduleMixedSearch()
    }

    /**
     * The source mix changed under the search on screen: it runs again after the live-query pause, a newer change
     * restarting the wait and the request in flight dropped, so a slider drag ends in one search. Before any search
     * has run, the mix only prepares the next one.
     */
    private fun scheduleMixedSearch() {
        val current = mutableState.value
        if (!current.hasCommittedSearch) return
        if (current.execution.request?.isPage == false) cancelActiveRequest()
        liveSearch.schedule(singleSource = false)
    }

    private fun reconcileEnvironment(settings: AppSettings, scheduleRetry: Boolean): Boolean {
        val hadExecutedSearch = mutableState.value.content.hasExecutedSearch
        val settingsChanged = runtime != settings.runtime
        runtime = settings.runtime
        val current = mutableState.value
        val availableSources = coordinator.availableSources
        val available = availableSources.toSet()
        val sourcesChanged = available != current.query.availableSources.toSet()
        val next = SearchDraftReducer.reconcileEnvironment(
            current,
            availableSources = availableSources,
            enabledSources = settings.runtime.enabledSources,
            sourceTermLimits = coordinator.sourceTermLimits(),
            scopesBySource = coordinator.searchScopesBySource(),
            exactTagSources = coordinator.exactTagSources(),
        )
        mutableState.value = next
        val draftChanged = next.query.draft != current.query.draft
        val appliedChanged = next.query.applied != current.query.applied
        persistDraftQuery()
        val requiresRetry = hadExecutedSearch && (settingsChanged || sourcesChanged || draftChanged || appliedChanged)
        if (requiresRetry && scheduleRetry) {
            rerunApplied(
                relatedResetReason = "environment: runtime=$settingsChanged sources=$sourcesChanged " +
                    "draft=$draftChanged applied=$appliedChanged",
            )
        }
        return requiresRetry
    }

    private fun mutateDraft(mutation: (SearchUiState) -> SearchUiState) {
        mutableState.value = mutation(mutableState.value)
        persistDraftQuery()
        scheduleLiveSearch()
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
        scheduleLiveSearch()
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
        rerunApplied(paintCachedFirstPage = true)
    }

    /** Runs the draft as the new search. Its first results make it the applied search; see [SearchSettlement]. */
    private fun runDraft(
        kind: SearchRequestKind = SearchRequestKind.REPLACE,
        acceptance: SearchAcceptance = SearchAcceptance.Settled,
    ) {
        val query = mutableState.value.query
        launchRootSearch(kind, query.draft, query.draftSourceScope, acceptance)
    }

    /** Runs the applied search again. It is already the accepted one, so nothing about it is persisted anew. */
    private fun rerunApplied(
        relatedResetReason: String = "root search (${SearchRequestKind.RETRY})",
        paintCachedFirstPage: Boolean = false,
    ) {
        val query = mutableState.value.query
        launchRootSearch(
            SearchRequestKind.RETRY, query.applied, query.appliedSourceScope,
            acceptance = null, relatedResetReason, paintCachedFirstPage,
        )
    }

    private fun launchRootSearch(
        kind: SearchRequestKind,
        query: Query,
        sourceScope: SearchSourceScope,
        acceptance: SearchAcceptance?,
        relatedResetReason: String = "root search ($kind)",
        paintCachedFirstPage: Boolean = false,
    ) {
        relatedPosts.clear(relatedResetReason)
        liveSearch.cancel()
        firstSearchSuperseded = false
        cancelActiveRequest()
        val executionKey = searchExecutionKey(query, sourceScope)
        acceptance?.let {
            settlement.begin(settle = it == SearchAcceptance.Settled, steppedTo = executionKey.takeIf { _ -> it is SearchAcceptance.Step })
        }
        val request = SearchRequest(++nextRequestId, kind, query, sourceScope, executionKey)
        reduce(SearchStateChange.BeginRequest(request))
        launchRequest(request, failureMessage = "Search failed") {
            try {
                if (paintCachedFirstPage) {
                    val posts = executionService.cachedFirstPage(request.executionKey, sourcesFor(request.sourceScope).enabled)
                    reduce(SearchStateChange.ShowCachedFirstPage(request.id, posts))
                }
                executeRootRequest(request, acceptance)
            } catch (_: TimeoutCancellationException) {
                reduce(SearchStateChange.RequestFailed(request.id, ROOT_REQUEST_TIMEOUT_MESSAGE))
            }
        }
    }

    /**
     * Runs [block] as the job of [request]. However it ends, the reducer hears of it, and ignores the
     * news when a newer request has since taken over.
     */
    private fun launchRequest(request: SearchRequest, failureMessage: String, block: suspend CoroutineScope.() -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reduce(SearchStateChange.RequestFailed(request.id, error.message ?: failureMessage))
            } finally {
                if (!isActive) reduce(SearchStateChange.RequestCancelled(request.id))
            }
        }
        activeRequestJob = job
        job.invokeOnCompletion { if (activeRequestJob === job) activeRequestJob = null }
        job.start()
    }

    private suspend fun executeRootRequest(request: SearchRequest, acceptance: SearchAcceptance?) =
        withTimeout(rootRequestTimeoutMs) {
            val publisher = RootResultPublisher(request, acceptance)
            val sources = sourcesFor(request.sourceScope)
            val result = executionService.executeInitial(request.query, request.sourceScope, sources) { partial ->
                if (mutableState.value.showsPartialResults(request.id, partial) &&
                    publisher.publish(partial, SearchStateChange.PartialResult(request.id, partial)) && acceptance != null
                ) {
                    settlement.partial(partial)
                }
            }
            coroutineContext.ensureActive()
            if (!mutableState.value.admits(request.id, result.executionKey)) {
                reduce(SearchStateChange.RequestCancelled(request.id))
                return@withTimeout
            }
            if (!publisher.publish(result, SearchStateChange.RootResult(request.id, result))) return@withTimeout
            rememberFirstPage(result)
            persistDraftQuery()
            if (acceptance != null) settlement.answered(result)
        }

    /** Puts one root request's results on screen; persisting it and resetting the scroll target happen only once. */
    private inner class RootResultPublisher(
        private val request: SearchRequest,
        acceptance: SearchAcceptance?,
    ) {
        private var pendingAcceptance = acceptance

        /** Applies [change], which shows [result]. False when this request no longer owns the screen. */
        suspend fun publish(result: SearchExecutionResult, change: SearchStateChange): Boolean {
            val acceptance = pendingAcceptance
            if (acceptance != null) scrollPersistence.reset(result.executionKey) {
                executionService.persistAppliedSearch(result.query, result.sourceScope, result.executionKey)
            }
            if (!mutableState.value.admits(request.id, result.executionKey)) return false
            val previous = mutableState.value
            reduce(change)
            pendingAcceptance = null
            if (acceptance != null) showAcceptedSearch(previous, acceptance)
            return true
        }
    }

    private fun rememberFirstPage(result: SearchExecutionResult) {
        if (result is SearchExecutionResult.Success && result.posts.isNotEmpty()) {
            viewModelScope.launch { executionService.rememberFirstPage(result.executionKey, result.posts) }
        }
    }

    /** A new search's first results are on screen: note the place left behind, then move to its own place. */
    private fun showAcceptedSearch(previous: SearchUiState, acceptance: SearchAcceptance) {
        settlement.shown(previous, savedStateHandle.savedSearchScrollState() ?: SearchScrollState(0, 0))
        val restoredPlace = (acceptance as? SearchAcceptance.Step)?.scrollState
        val scrollState = restoredPlace ?: SearchScrollState(0, 0)
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = scrollState.firstVisibleItemIndex
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = scrollState.firstVisibleItemOffsetPx
        reduce(
            if (restoredPlace != null) SearchStateChange.RouteEntryScrollRestorationRequested(scrollState)
            else SearchStateChange.ScrollResetRequested,
        )
    }

    private fun retry() {
        val current = mutableState.value
        if (current.content.error?.requestKind == SearchRequestKind.PAGE) {
            launchPage(current.execution.pageRetrySource)
        } else {
            rerunApplied()
        }
    }

    private fun retrySource(source: SourceKey) {
        val current = mutableState.value
        if (current.execution.activeRequestId != null || current.content.statuses.none {
            it.source == source && it.state == com.theoriacodex.domain.orchestration.SourceRunState.FAILED
        }) return
        launchPage(source)
    }

    /** Loads the next page of the results on screen, or with [retrySource] asks that one source again. */
    private fun launchPage(retrySource: SourceKey? = null) {
        val current = mutableState.value
        val continuation = current.execution.continuation
        cancelActiveRequest()
        val request = SearchRequest(
            id = ++nextRequestId,
            kind = SearchRequestKind.PAGE,
            query = continuation?.query ?: current.query.applied,
            sourceScope = current.query.appliedSourceScope,
            executionKey = continuation?.executionKey ?: current.query.appliedQueryHash,
        )
        reduce(SearchStateChange.BeginRequest(request, retrySource))
        launchRequest(request, failureMessage = "Could not load more results") {
            val result = if (retrySource == null) executionService.executePage(requireNotNull(continuation))
            else executionService.retrySource(
                request.query, request.sourceScope, sourcesFor(request.sourceScope), continuation, retrySource,
            )
            coroutineContext.ensureActive()
            reduce(SearchStateChange.PageResult(request.id, result))
        }
    }

    private fun cancelActiveRequest() {
        val requestId = mutableState.value.execution.activeRequestId
        if (requestId != null) settlement.superseded()
        activeRequestJob?.cancel(CancellationException("Search route request cancelled"))
        activeRequestJob = null
        requestId?.let { reduce(SearchStateChange.RequestCancelled(it)) }
    }

    private fun restore() {
        if (restorationJob?.isActive == true) return
        if (mutableState.value.restoration is SearchRestorationUiState.Restored) return
        reduce(SearchStateChange.BeginRestoration)
        savedStateHandle[SearchSavedStateKeys.RESTORATION_STARTED] = true
        restorationJob = viewModelScope.launch {
            try {
                val initialization = coordinator.initializeRoute()
                runtime = initialization.runtime
                val appliedHash = searchExecutionKey(
                    initialization.query,
                    initialization.sourceScope,
                )
                mutableState.value = SearchStateReducer.restore(initialization, appliedHash)
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
                    rerunApplied()
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
        val reduction = SearchDraftReducer.restoreDraft(mutableState.value, savedQuery)
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
        if (action.firstVisibleItemIndex > 0 && mutableState.value.execution.request?.cachedFirstPage == CachedFirstPage.SHOWN) {
            reduce(SearchStateChange.CachedFirstPageScrolled)
        }
        if (!mutableState.value.restoration.allowsScrollPersistence) return
        val index = action.firstVisibleItemIndex.coerceAtLeast(0)
        val offset = action.firstVisibleItemOffsetPx.coerceAtLeast(0)
        savedStateHandle[SearchSavedStateKeys.SCROLL_INDEX] = index
        savedStateHandle[SearchSavedStateKeys.SCROLL_OFFSET] = offset
        // Scrolling the results of a finished search means the user has taken it: it settles.
        if ((index > 0 || offset > 0) && !mutableState.value.loading) settlement.settle()
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
    /** What a search in [scope] runs on under the current source settings. */
    private fun sourcesFor(scope: SearchSourceScope): SearchSources = SearchSources.of(
        runtime ?: SourceRuntimeSettings(), scope, coordinator.availableSources.toSet(),
    )

    private fun clearSuggestionState() {
        suggestionRefresh.cancelAutocomplete()
        val current = mutableState.value
        mutableState.value = current.copy(suggestions = SearchSuggestionsUiState())
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

    /** How a new search is treated once its first results are on screen. */
    private sealed interface SearchAcceptance {
        /** Follows a draft edit; it is remembered only once it settles. */
        data object Live : SearchAcceptance

        /** Asked for outright, so it settles at once. */
        data object Settled : SearchAcceptance

        /** A step through history, back to [scrollState], the place it was left at. */
        data class Step(val scrollState: SearchScrollState) : SearchAcceptance
    }

    private companion object {
        const val DEFAULT_AUTOCOMPLETE_DELAY_MS = 300L
        const val DEFAULT_ROOT_REQUEST_TIMEOUT_MS = 60_000L
        const val DEFAULT_SCROLL_PERSISTENCE_DELAY_MS = 150L
        const val SEARCH_SCROLL_PERSISTENCE_KEY = "search-scroll-persistence"
        const val ROOT_REQUEST_TIMEOUT_MESSAGE = "Search timed out. Check your connection and try again."
    }
}

private fun SearchUiState.viewerFeed() = ViewerFeedSnapshot(
    queryHash = query.appliedQueryHash.takeIf(String::isNotBlank),
    results = content.results,
    canLoadMore = content.canLoadMore,
    loadingMore = loadingMore,
    retainedLikedPostIds = relatedPosts.retainedLikedPostIds,
    relatedPosts = relatedPosts,
)
