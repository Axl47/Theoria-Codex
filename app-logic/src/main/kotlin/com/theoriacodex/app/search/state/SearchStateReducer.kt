package com.theoriacodex.app.search.state

import com.theoriacodex.app.feed.mergeFeedStatuses
import com.theoriacodex.app.search.SearchContinuation
import com.theoriacodex.app.search.SearchExecutionResult
import com.theoriacodex.app.search.SearchInitialization
import com.theoriacodex.app.search.SearchPageResult
import com.theoriacodex.app.search.showsPartialResults
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.distinctByFile
import com.theoriacodex.domain.model.plusDistinctFiles
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash

sealed interface SearchStateChange {
    data class BeginRequest(
        val request: SearchRequest,
        val retrySource: SourceKey? = null,
    ) : SearchStateChange

    data class RootResult(
        val requestId: Long,
        val result: SearchExecutionResult,
    ) : SearchStateChange

    data class PartialResult(
        val requestId: Long,
        val result: SearchExecutionResult.Success,
    ) : SearchStateChange

    data class ShowCachedFirstPage(
        val requestId: Long,
        val posts: List<Post>,
    ) : SearchStateChange

    data object CachedFirstPageScrolled : SearchStateChange

    data class PageResult(
        val requestId: Long,
        val result: SearchPageResult,
    ) : SearchStateChange

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
    fun restore(initialization: SearchInitialization, appliedHash: String): SearchUiState = SearchUiState(
        query = SearchQueryUiState(
            draft = initialization.query,
            applied = initialization.query,
            draftSourceScope = initialization.sourceScope,
            appliedSourceScope = initialization.sourceScope,
            appliedQueryHash = appliedHash,
            availableSources = initialization.availableSources,
            unifiedSources = initialization.runtime.enabledSources.intersect(initialization.availableSources.toSet()),
            scopesBySource = initialization.scopesBySource,
            exactTagSources = initialization.exactTagSources,
            validationMessage = initialization.validationMessage,
            appliedByMode = initialization.appliedByMode,
        ),
        content = SearchContentUiState(hasExecutedSearch = initialization.hasExecutedSearch),
        restoration = SearchRestorationUiState.Restoring,
        history = SearchQueryHistory(
            current = SearchHistoryEntry(initialization.query, initialization.sourceScope)
                .takeIf { initialization.hasExecutedSearch },
        ),
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

    private fun SearchRequest.refreshed(shown: List<Post>, fresh: List<Post>): List<Post> {
        if (cachedFirstPage != CachedFirstPage.SCROLLED) return fresh
        val freshById = fresh.associateBy(Post::id)
        return shown.map { post -> freshById[post.id]?.withPictureHashOf(post) ?: post }.plusDistinctFiles(fresh)
    }

    private fun Post.withPictureHashOf(cached: Post): Post =
        if (pictureHash == null && preview == cached.preview && cached.pictureHash != null) {
            copy(pictureHash = cached.pictureHash)
        } else this

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
        if (result.executionKey != request.executionKey || state.query.appliedQueryHash != request.executionKey) {
            return cancelRequest(state, request.id)
        }
        return when (result) {
            is SearchPageResult.Success -> SearchReduction(
                state.copy(
                    content = state.content.copy(
                        results = state.content.results.plusDistinctFiles(result.posts),
                        statuses = mergeFeedStatuses(state.content.statuses, result.statuses),
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

            is SearchPageResult.Failure -> SearchReduction(
                state.withFailedRequest(request, result.message, result.statuses, null, state.execution.continuation),
            )
        }
    }

    private fun requestFailed(state: SearchUiState, change: SearchStateChange.RequestFailed): SearchReduction {
        val request = state.activeRequest(change.requestId) ?: return SearchReduction(state)
        val attempted = AppliedSearch(request.query, request.sourceScope, request.executionKey).takeUnless { request.isPage }
        return SearchReduction(
            state.withFailedRequest(request, change.message, emptyList(), attempted, state.execution.continuation),
        )
    }

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
                    mergeFeedStatuses(content.statuses, statuses)
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

    private fun cancelRequest(state: SearchUiState, requestId: Long): SearchReduction {
        if (state.activeRequest(requestId) == null) return SearchReduction(state)
        return SearchReduction(
            state.copy(execution = state.execution.copy(request = null, lastCancelledRequestId = requestId)),
        )
    }
}

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
