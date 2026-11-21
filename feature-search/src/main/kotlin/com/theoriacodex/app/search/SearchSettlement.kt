package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.appliedHistoryEntry
import com.theoriacodex.data.repository.SearchScrollState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decides when a live search settles. Results follow the draft as it changes, but a search is remembered only
 * once the user is done with it: the composer closes on it, a post is opened from it, or its results are first
 * scrolled. Settling moves it into the route's history and records it in Recents ([record]); a search replaced
 * before it settles leaves no trace. A search the user asks for outright (a saved search, Recents, a tag from
 * elsewhere) settles as soon as its results show.
 */
internal class SearchSettlement(
    private val state: MutableStateFlow<SearchUiState>,
    private val scope: CoroutineScope,
    private val record: suspend (SearchExecutionResult) -> Unit,
) {
    private var settleWhenShown = false

    /** The running search's final answer, kept until it settles and is recorded. */
    private var answer: SearchExecutionResult? = null
    private var settledKey: String? = null
    private var recordedKey: String? = null

    /** A new search starts; [settle] when it was asked for outright rather than edited live. */
    fun begin(settle: Boolean) {
        settleWhenShown = settle
        answer = null
    }

    /** The new search's first results replaced those of [previous], which was scrolled to [leftAt]. */
    fun shown(previous: SearchUiState, leftAt: SearchScrollState) {
        val current = state.value
        val settled = current.history.current
        if (previous.appliedHistoryEntry().sameSearch(settled) && !current.appliedHistoryEntry().sameSearch(settled)) {
            state.value = current.copy(history = current.history.leaving(leftAt))
        }
        if (settleWhenShown) settle()
    }

    /** The final answer of the search on screen; recorded once that search settles. */
    fun answered(result: SearchExecutionResult) {
        answer = result
        recordIfSettled()
    }

    /** Settles the search on screen. A new search whose results have not shown yet settles once they do. */
    fun settle() {
        val current = state.value
        val request = current.execution.request
        if (request != null && request.kind != SearchRequestKind.PAGE && request.kind != SearchRequestKind.RETRY &&
            request.shownQuery == null
        ) {
            settleWhenShown = true
            return
        }
        settleWhenShown = false
        val key = current.query.appliedQueryHash
        if (key == settledKey && current.appliedHistoryEntry().sameSearch(current.history.current)) return
        state.value = current.copy(history = current.history.settled(current.appliedHistoryEntry()))
        settledKey = key
        recordIfSettled()
    }

    private fun recordIfSettled() {
        val result = answer?.takeIf { it.executionKey == settledKey && it.executionKey != recordedKey } ?: return
        recordedKey = result.executionKey
        // Once started, recording finishes even if the user leaves Search.
        scope.launch { withContext(NonCancellable) { record(result) } }
    }
}
