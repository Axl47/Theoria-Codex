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

internal class SearchSettlement(
    private val state: MutableStateFlow<SearchUiState>,
    private val scope: CoroutineScope,
    private val record: suspend (SearchExecutionResult) -> Unit,
) {
    private var settleWhenShown = false

    private var answer: SearchExecutionResult? = null
    private var answerIsFinal = false
    private var settledKey: String? = null
    private var recordedKey: String? = null

    fun begin(settle: Boolean, steppedTo: String? = null) {
        settleWhenShown = settle
        answer = null
        answerIsFinal = false
        if (steppedTo != null) {
            settledKey = steppedTo
            recordedKey = steppedTo
        }
    }

    fun shown(previous: SearchUiState, leftAt: SearchScrollState) {
        val current = state.value
        val settled = current.history.current
        if (previous.appliedHistoryEntry().sameSearch(settled) && !current.appliedHistoryEntry().sameSearch(settled)) {
            state.value = current.copy(history = current.history.leaving(leftAt))
        }
        if (settleWhenShown) settle()
    }

    fun partial(result: SearchExecutionResult) {
        if (answerIsFinal) return
        answer = result
    }

    fun answered(result: SearchExecutionResult) {
        answer = result
        answerIsFinal = true
        recordIfSettled()
    }

    fun superseded() {
        recordIfSettled(acceptPartial = true)
    }

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

    private fun recordIfSettled(acceptPartial: Boolean = false) {
        if (!answerIsFinal && !acceptPartial) return
        val result = answer?.takeIf { it.executionKey == settledKey && it.executionKey != recordedKey } ?: return
        recordedKey = result.executionKey
        scope.launch { withContext(NonCancellable) { record(result) } }
    }
}
