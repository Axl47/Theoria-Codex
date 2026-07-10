package com.theoriacodex.app.search.state

import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash

sealed interface SearchStateChange {
    data class BeginRequest(
        val requestId: Long,
        val kind: SearchRequestKind,
        val submittedQuery: Query,
    ) : SearchStateChange

    data class ReplaceResults(
        val requestId: Long,
        val appliedQuery: Query,
        val results: List<Post>,
        val statuses: List<SourceRunStatus>,
        val canLoadMore: Boolean,
    ) : SearchStateChange

    data class AppendPage(
        val requestId: Long,
        val results: List<Post>,
        val statuses: List<SourceRunStatus>,
        val canLoadMore: Boolean,
    ) : SearchStateChange

    data class RequestFailed(
        val requestId: Long,
        val message: String,
        val statuses: List<SourceRunStatus> = emptyList(),
        val retryable: Boolean = true,
    ) : SearchStateChange

    data class RequestCancelled(
        val requestId: Long,
    ) : SearchStateChange

    data object BeginRestoration : SearchStateChange

    data class RestorationCompleted(
        val restoredQuery: Boolean,
        val scrollState: SearchScrollState? = null,
    ) : SearchStateChange

    data class RestorationFailed(
        val message: String,
    ) : SearchStateChange
}

object SearchStateReducer {
    fun reduce(state: SearchUiState, change: SearchStateChange): SearchReduction {
        return when (change) {
            is SearchStateChange.BeginRequest -> {
                val nextState = if (
                    state.execution.activeRequestId != null &&
                    change.requestId <= state.execution.activeRequestId
                ) {
                    state
                } else {
                    state.copy(
                        content = state.content.copy(error = null),
                        execution = state.execution.copy(
                            activeRequestId = change.requestId,
                            activeKind = change.kind,
                            submittedQuery = change.submittedQuery,
                        ),
                    )
                }
                SearchReduction(state = nextState)
            }

            is SearchStateChange.ReplaceResults -> replaceResults(state, change)
            is SearchStateChange.AppendPage -> appendPage(state, change)
            is SearchStateChange.RequestFailed -> failRequest(state, change)
            is SearchStateChange.RequestCancelled -> cancelRequest(state, change)
            SearchStateChange.BeginRestoration -> SearchReduction(
                state = state.copy(restoration = SearchRestorationUiState.Restoring),
            )

            is SearchStateChange.RestorationCompleted -> SearchReduction(
                state = state.copy(
                    restoration = SearchRestorationUiState.Restored(
                        restoredQuery = change.restoredQuery,
                        scrollState = change.scrollState,
                    ),
                ),
            )

            is SearchStateChange.RestorationFailed -> SearchReduction(
                state = state.copy(
                    restoration = SearchRestorationUiState.Failed(change.message),
                ),
            )
        }
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
            state = state,
            effects = listOf(
                SearchEffect.OpenViewer(
                    posts = action.visibleResults.toList(),
                    context = context,
                ),
            ),
        )
    }

    private fun replaceResults(
        state: SearchUiState,
        change: SearchStateChange.ReplaceResults,
    ): SearchReduction {
        if (!state.isCurrent(change.requestId)) return SearchReduction(state)
        val submittedQuery = state.execution.submittedQuery
        val draft = if (state.query.draft == submittedQuery) {
            change.appliedQuery
        } else {
            state.query.draft
        }
        return SearchReduction(
            state = state.copy(
                query = state.query.copy(
                    draft = draft,
                    applied = change.appliedQuery,
                    appliedQueryHash = QueryHash.from(change.appliedQuery),
                ),
                content = state.content.copy(
                    results = change.results.toList(),
                    statuses = change.statuses.toList(),
                    canLoadMore = change.canLoadMore,
                    hasExecutedSearch = true,
                    error = null,
                    displayVersion = state.content.displayVersion + 1,
                ),
                execution = state.execution.completed(change.requestId),
            ),
        )
    }

    private fun appendPage(
        state: SearchUiState,
        change: SearchStateChange.AppendPage,
    ): SearchReduction {
        if (!state.isCurrent(change.requestId)) return SearchReduction(state)
        val combined = (state.content.results + change.results)
            .distinctBy { post -> post.id }
        return SearchReduction(
            state = state.copy(
                content = state.content.copy(
                    results = combined,
                    statuses = change.statuses.toList(),
                    canLoadMore = change.canLoadMore,
                    error = null,
                    displayVersion = state.content.displayVersion + 1,
                ),
                execution = state.execution.completed(change.requestId),
            ),
        )
    }

    private fun failRequest(
        state: SearchUiState,
        change: SearchStateChange.RequestFailed,
    ): SearchReduction {
        if (!state.isCurrent(change.requestId)) return SearchReduction(state)
        return SearchReduction(
            state = state.copy(
                content = state.content.copy(
                    statuses = change.statuses.ifEmpty { state.content.statuses },
                    error = SearchErrorUiState(
                        message = change.message,
                        requestKind = state.execution.activeKind,
                        retryable = change.retryable,
                    ),
                ),
                execution = state.execution.copy(
                    activeRequestId = null,
                    activeKind = null,
                    submittedQuery = null,
                ),
            ),
        )
    }

    private fun cancelRequest(
        state: SearchUiState,
        change: SearchStateChange.RequestCancelled,
    ): SearchReduction {
        if (!state.isCurrent(change.requestId)) return SearchReduction(state)
        return SearchReduction(
            state = state.copy(
                execution = state.execution.copy(
                    activeRequestId = null,
                    activeKind = null,
                    submittedQuery = null,
                    lastCancelledRequestId = change.requestId,
                ),
            ),
        )
    }
}

private fun SearchUiState.isCurrent(requestId: Long): Boolean {
    return execution.activeRequestId == requestId
}

private fun SearchExecutionUiState.completed(requestId: Long): SearchExecutionUiState {
    return copy(
        activeRequestId = null,
        activeKind = null,
        submittedQuery = null,
        lastCompletedRequestId = requestId,
    )
}
