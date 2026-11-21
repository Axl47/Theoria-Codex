package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.SearchContinuation
import com.theoriacodex.app.search.SearchExecutionResult
import com.theoriacodex.app.search.SearchInitialization
import com.theoriacodex.app.search.SearchPageResult
import com.theoriacodex.app.search.showsPartialResults
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.distinctByFile
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash

sealed interface SearchStateChange {
    /** [retrySource] is the one source a page request retries; a root request and a plain next page have none. */
    data class BeginRequest(
        val request: SearchRequest,
        val retrySource: SourceKey? = null,
    ) : SearchStateChange

    /** A root request's complete answer. */
    data class RootResult(
        val requestId: Long,
        val result: SearchExecutionResult,
    ) : SearchStateChange

    /** Results that arrived while their request keeps running; paging waits for the request to finish. */
    data class PartialResult(
        val requestId: Long,
        val result: SearchExecutionResult.Success,
    ) : SearchStateChange

    /** The last run's first page of the search now rerunning, to show until the fresh one arrives. */
    data class ShowCachedFirstPage(
        val requestId: Long,
        val posts: List<Post>,
    ) : SearchStateChange

    /** The user moved away from the top of a cached first page. */
    data object CachedFirstPageScrolled : SearchStateChange

    /** A page request's answer: the next page, or one source retried. */
    data class PageResult(
        val requestId: Long,
        val result: SearchPageResult,
    ) : SearchStateChange

    /** The request ended without an answer: it threw or ran out of time. */
    data class RequestFailed(
        val requestId: Long,
        val message: String,
    ) : SearchStateChange

    data class RequestCancelled(
        val requestId: Long,
    ) : SearchStateChange
    data object BeginRestoration : SearchStateChange

    data class RestorationCompleted(
        val restoredQuery: Boolean,
        val scrollState: SearchScrollState? = null,
    ) : SearchStateChange

    data class RouteEntryScrollRestorationRequested(
        val scrollState: SearchScrollState,
        val keepLiveGrid: Boolean = false,
    ) : SearchStateChange

    data object ScrollResetRequested : SearchStateChange

    data class ScrollRestorationApplied(
        val requestId: Long,
    ) : SearchStateChange

    data class RestorationFailed(
        val message: String,
    ) : SearchStateChange
}

object SearchStateReducer {
    /** Builds restored state: the stored search is both the draft and the applied search. */
    fun restore(
        initialization: SearchInitialization,
        appliedHash: String,
        supportedScopes: (QueryMode) -> List<FacetedSearchScope>,
    ): SearchUiState = SearchUiState(
        query = SearchQueryUiState(
            draft = initialization.query,
            applied = initialization.query,
            draftSourceScope = initialization.sourceScope,
            appliedSourceScope = initialization.sourceScope,
            appliedQueryHash = appliedHash,
            availableSources = initialization.availableSources,
            modeOptions = listOf(QueryMode.Unified) + initialization.availableSources.map(QueryMode::Source),
            enabledSourceCount = initialization.availableSources.size,
            unifiedSources = initialization.runtime.enabledSources.intersect(initialization.availableSources.toSet()),
            supportedScopes = supportedScopes(initialization.query.mode),
            validationMessage = initialization.validationMessage,
            nhentaiLanguageFilter = initialization.query.nhentaiLanguageFilter(),
            nhentaiFullColorFilter = initialization.query.nhentaiFullColorFilter(),
            appliedByMode = initialization.appliedByMode,
        ),
        content = SearchContentUiState(hasExecutedSearch = initialization.hasExecutedSearch),
        restoration = SearchRestorationUiState.Restoring,
    )

    fun reduce(state: SearchUiState, change: SearchStateChange): SearchReduction {
        return when (change) {
            is SearchStateChange.BeginRequest -> beginRequest(state, change)

            is SearchStateChange.RootResult -> rootResult(state, change)

            is SearchStateChange.PartialResult -> partialResult(state, change)

            is SearchStateChange.ShowCachedFirstPage -> showCachedFirstPage(state, change)

            SearchStateChange.CachedFirstPageScrolled -> cachedFirstPageScrolled(state)

            is SearchStateChange.PageResult -> pageResult(state, change)

            is SearchStateChange.RequestFailed -> requestFailed(state, change)

            is SearchStateChange.RequestCancelled -> cancelRequest(state, change.requestId)

            SearchStateChange.BeginRestoration -> SearchReduction(
                state = state.copy(restoration = SearchRestorationUiState.Restoring),
            )

            is SearchStateChange.RestorationCompleted -> SearchReduction(
                state = state.copy(
                    restoration = SearchRestorationUiState.Restored(
                        restoredQuery = change.restoredQuery,
                        scrollState = change.scrollState,
                        scrollRequestId = if (change.scrollState == null) 0L else 1L,
                    ),
                ),
            )

            is SearchStateChange.RouteEntryScrollRestorationRequested ->
                requestScrollPosition(state, change.scrollState, keepLiveGrid = change.keepLiveGrid)

            SearchStateChange.ScrollResetRequested ->
                requestScrollPosition(state, SearchScrollState(0, 0), resetToTop = true)

            is SearchStateChange.ScrollRestorationApplied ->
                applyScrollRestoration(state, change)

            is SearchStateChange.RestorationFailed -> SearchReduction(
                state = state.copy(
                    restoration = SearchRestorationUiState.Failed(change.message),
                ),
            )
        }
    }

    private fun requestScrollPosition(
        state: SearchUiState,
        scrollState: SearchScrollState,
        resetToTop: Boolean = false,
        keepLiveGrid: Boolean = false,
    ): SearchReduction {
        val restored = state.restoration as? SearchRestorationUiState.Restored
            ?: return SearchReduction(state)
        // A keep-live request may be left undone on a grid showing posts, so it must not replace an open
        // request that has to move the grid, such as the startup restore or a reset to top.
        if (keepLiveGrid && restored.scrollState != null && !restored.keepLiveGrid) return SearchReduction(state)
        return SearchReduction(
            state = state.copy(
                restoration = restored.copy(
                    scrollState = scrollState,
                    scrollRequestId = restored.scrollRequestId + 1L,
                    resetToTop = resetToTop,
                    keepLiveGrid = keepLiveGrid,
                ),
            ),
        )
    }

    private fun applyScrollRestoration(
        state: SearchUiState,
        change: SearchStateChange.ScrollRestorationApplied,
    ): SearchReduction {
        val restored = state.restoration as? SearchRestorationUiState.Restored
            ?: return SearchReduction(state)
        if (restored.scrollRequestId != change.requestId) return SearchReduction(state)
        return SearchReduction(state.withScrollRequestClosed())
    }

    private fun SearchUiState.withScrollRequestClosed(): SearchUiState {
        val restored = restoration as? SearchRestorationUiState.Restored ?: return this
        return copy(restoration = restored.copy(scrollState = null, resetToTop = false, keepLiveGrid = false))
    }

    fun reduce(state: SearchUiState, action: SearchAction.OpenResult): SearchReduction {
        val startIndex = action.visibleResults.indexOfFirst { post -> post.id == action.postId }
        if (startIndex < 0) {
            return SearchReduction(
                state = state,
                effects = listOf(SearchEffect.ShowMessage("That result is no longer available")),
            )
        }
        val context = ViewerLaunchContext(
            queryHash = state.query.appliedQueryHash.ifBlank {
                QueryHash.from(state.query.applied)
            },
            startIndex = startIndex,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = action.scrollOffsetHint.coerceAtLeast(0),
        )
        // Opening a post shows the user has taken over the grid, like a drag: a pending restore must not jump
        // the grid to an old anchor after they return from the Viewer.
        return SearchReduction(
            state = state.withScrollRequestClosed(),
            effects = listOf(
                SearchEffect.OpenViewer(
                    posts = action.visibleResults.toList(),
                    context = context,
                    visibilityFilters = action.visibilityFilters,
                ),
            ),
        )
    }

    private fun beginRequest(state: SearchUiState, change: SearchStateChange.BeginRequest): SearchReduction {
        val active = state.execution.request
        if (active != null && change.request.id <= active.id) return SearchReduction(state)
        val page = change.request.isPage
        return SearchReduction(
            state.copy(
                content = state.content.copy(error = null),
                execution = state.execution.copy(
                    request = change.request,
                    // A new search replaces the results, so nothing continues from them.
                    continuation = state.execution.continuation.takeIf { page },
                    pageRetrySource = change.retrySource.takeIf { page },
                ),
            ),
        )
    }

    private fun rootResult(state: SearchUiState, change: SearchStateChange.RootResult): SearchReduction {
        val request = state.activeRequest(change.requestId) ?: return SearchReduction(state)
        val result = change.result
        if (result.executionKey != request.executionKey) return cancelRequest(state, request.id)
        val applied = AppliedSearch(result.query, result.sourceScope, result.executionKey)
        return when (result) {
            is SearchExecutionResult.Success -> {
                val published = state.withPublishedResults(
                    applied, request.refreshed(state.content.results, result.posts), result.statuses,
                    canLoadMore = result.continuation.canLoadMore,
                )
                SearchReduction(
                    published.copy(
                        execution = published.execution.completed(request.id).copy(continuation = result.continuation),
                    ),
                )
            }

            is SearchExecutionResult.Failure -> SearchReduction(
                state.withFailedRequest(request, result.message, result.statuses, applied, result.continuation),
            )
        }
    }

    private fun partialResult(state: SearchUiState, change: SearchStateChange.PartialResult): SearchReduction {
        val request = state.execution.request
        if (request == null || !state.showsPartialResults(change.requestId, change.result)) return SearchReduction(state)
        val result = change.result
        val published = state.withPublishedResults(
            AppliedSearch(result.query, result.sourceScope, result.executionKey), result.posts, result.statuses,
            canLoadMore = false,
        )
        // The applied query may be a normalized form of what was asked.
        return SearchReduction(published.withRequest(request.copy(shownQuery = result.query)))
    }

    private fun showCachedFirstPage(
        state: SearchUiState,
        change: SearchStateChange.ShowCachedFirstPage,
    ): SearchReduction {
        val request = state.activeRequest(change.requestId)
        if (request == null || change.posts.isEmpty()) return SearchReduction(state)
        val published = state.withPublishedResults(
            AppliedSearch(request.query, request.sourceScope, request.executionKey), change.posts, emptyList(),
            canLoadMore = false,
            // Nothing has answered yet, so this is not yet a search the mode has run.
            recordForMode = false,
        )
        return SearchReduction(
            published.withRequest(request.copy(shownQuery = request.query, cachedFirstPage = CachedFirstPage.SHOWN)),
        )
    }

    private fun cachedFirstPageScrolled(state: SearchUiState): SearchReduction {
        val request = state.execution.request?.takeIf { it.cachedFirstPage == CachedFirstPage.SHOWN }
            ?: return SearchReduction(state)
        return SearchReduction(state.withRequest(request.copy(cachedFirstPage = CachedFirstPage.SCROLLED)))
    }

    /** Someone who has scrolled the cached page keeps their place: its posts stay put and new ones go below. */
    private fun SearchRequest.refreshed(shown: List<Post>, fresh: List<Post>): List<Post> {
        if (cachedFirstPage != CachedFirstPage.SCROLLED) return fresh
        val freshById = fresh.associateBy(Post::id)
        return (shown.map { post -> freshById[post.id] ?: post } + fresh).distinctByFile()
    }

    private fun SearchUiState.withPublishedResults(
        applied: AppliedSearch,
        results: List<Post>,
        statuses: List<SourceRunStatus>,
        canLoadMore: Boolean,
        recordForMode: Boolean = true,
    ): SearchUiState = copy(
        query = query.withApplied(applied, execution.submittedQuery, recordForMode),
        content = content.copy(
            results = results.toList(),
            statuses = statuses.toList(),
            canLoadMore = canLoadMore,
            hasExecutedSearch = true,
            error = null,
            displayVersion = content.displayVersion + 1,
        ),
    )

    /** A draft still equal to what was asked follows the applied query, which may be a normalized form of it. */
    private fun SearchQueryUiState.withApplied(
        applied: AppliedSearch,
        submittedQuery: Query?,
        recordForMode: Boolean = true,
    ): SearchQueryUiState = copy(
        draft = if (draft == submittedQuery) applied.query else draft,
        applied = applied.query,
        appliedSourceScope = applied.sourceScope,
        appliedQueryHash = applied.hash,
        appliedByMode = if (recordForMode && applied.sourceScope !is SearchSourceScope.Temporary) {
            appliedByMode + (modeKey(applied.query.mode) to applied.query)
        } else {
            appliedByMode
        },
    )

    private fun pageResult(state: SearchUiState, change: SearchStateChange.PageResult): SearchReduction {
        val request = state.activeRequest(change.requestId) ?: return SearchReduction(state)
        val result = change.result
        // A page belongs to the results on screen only while they are still the search it was asked for.
        if (result.executionKey != request.executionKey || state.query.appliedQueryHash != request.executionKey) {
            return cancelRequest(state, request.id)
        }
        return when (result) {
            is SearchPageResult.Success -> SearchReduction(
                state.copy(
                    content = state.content.copy(
                        results = (state.content.results + result.posts).distinctByFile(),
                        statuses = mergeStatusesBySource(state.content.statuses, result.statuses),
                        canLoadMore = result.continuation.canLoadMore,
                        error = null,
                        displayVersion = state.content.displayVersion + 1,
                    ),
                    execution = state.execution.completed(request.id).copy(
                        continuation = result.continuation,
                        pageRetrySource = null,
                    ),
                ),
            )

            // The continuation and the retried source stay, so Retry repeats this page.
            is SearchPageResult.Failure -> SearchReduction(
                state.withFailedRequest(request, result.message, result.statuses, null, state.execution.continuation),
            )
        }
    }

    private fun requestFailed(state: SearchUiState, change: SearchStateChange.RequestFailed): SearchReduction {
        val request = state.activeRequest(change.requestId) ?: return SearchReduction(state)
        // A search that never answered still becomes the applied one, so Retry repeats it and not the one before.
        val attempted = AppliedSearch(request.query, request.sourceScope, request.executionKey).takeUnless { request.isPage }
        return SearchReduction(
            state.withFailedRequest(request, change.message, emptyList(), attempted, state.execution.continuation),
        )
    }

    /** [applied] is the search the failure belongs to; null leaves the results and their search as they are. */
    private fun SearchUiState.withFailedRequest(
        request: SearchRequest,
        message: String,
        statuses: List<SourceRunStatus>,
        applied: AppliedSearch?,
        continuation: SearchContinuation?,
    ): SearchUiState {
        val queryState = if (applied == null) query else query.withApplied(applied, execution.submittedQuery)
        return copy(
            query = queryState,
            content = content.copy(
                results = if (queryState.appliedQueryHash != query.appliedQueryHash) emptyList() else content.results,
                statuses = if (request.isPage && statuses.isNotEmpty()) {
                    mergeStatusesBySource(content.statuses, statuses)
                } else {
                    statuses.ifEmpty { content.statuses }
                },
                hasExecutedSearch = content.hasExecutedSearch || applied != null,
                canLoadMore = if (applied != null) false else content.canLoadMore,
                error = SearchErrorUiState(message = message, requestKind = request.kind, retryable = true),
            ),
            execution = execution.copy(request = null, continuation = continuation),
        )
    }

    private fun mergeStatusesBySource(
        current: List<SourceRunStatus>,
        updates: List<SourceRunStatus>,
    ): List<SourceRunStatus> = (current + updates)
        .associateBy(SourceRunStatus::source)
        .values
        .sortedBy { status -> status.source.id }

    private fun cancelRequest(state: SearchUiState, requestId: Long): SearchReduction {
        if (state.activeRequest(requestId) == null) return SearchReduction(state)
        return SearchReduction(
            state.copy(execution = state.execution.copy(request = null, lastCancelledRequestId = requestId)),
        )
    }
}

/** The search some results or a failure belong to. */
private data class AppliedSearch(
    val query: Query,
    val sourceScope: SearchSourceScope,
    val hash: String,
)

private fun SearchUiState.activeRequest(requestId: Long): SearchRequest? =
    execution.request?.takeIf { request -> request.id == requestId }

private fun SearchUiState.withRequest(request: SearchRequest): SearchUiState =
    copy(execution = execution.copy(request = request))

private fun SearchExecutionUiState.completed(requestId: Long): SearchExecutionUiState =
    copy(request = null, lastCompletedRequestId = requestId)
