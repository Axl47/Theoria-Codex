package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchLoadingResponsivenessTest : SearchViewModelTestFixture() {
    @Test
    fun `accepted results become visible before slow history bookkeeping`() = runTest(mainDispatcherRule.dispatcher) {
        val historyStarted = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val owner = viewModel(ViewModelSearchAdapter(), executionService = { coordinator ->
            object : SearchExecutionService by coordinator {
                override suspend fun recordAcceptedSearch(query: Query, sourceScope: SearchSourceScope, executionKey: String) {
                    historyStarted.complete(Unit)
                    historyRelease.await()
                    coordinator.recordAcceptedSearch(query, sourceScope, executionKey)
                }
            }
        })
        restore(owner)
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
        owner.onAction(SearchAction.ApplyDraft)
        runCurrent()
        historyStarted.await()
        assertEquals(listOf("fast-result"), resultIds(owner))
        assertFalse(owner.state.value.loading)
        historyRelease.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `same query refresh retains content while a different query does not`() = runTest(mainDispatcherRule.dispatcher) {
        val held = CompletableDeferred<Unit>()
        var hold = false
        val owner = viewModel(ViewModelSearchAdapter(), executionService = { coordinator ->
            object : SearchExecutionService by coordinator {
                override suspend fun executeInitial(query: Query, sourceScope: SearchSourceScope): SearchExecutionResult {
                    if (hold) held.await()
                    return coordinator.executeInitial(query, sourceScope)
                }
            }
        })
        restore(owner)
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
        owner.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        hold = true
        owner.onAction(SearchAction.ApplyDraft)
        runCurrent()
        assertTrue(owner.state.value.retainsResultsWhileRefreshing)
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("different")))
        owner.onAction(SearchAction.ApplyDraft)
        runCurrent()
        assertFalse(owner.state.value.retainsResultsWhileRefreshing)
        held.complete(Unit)
        advanceUntilIdle()
    }
}
