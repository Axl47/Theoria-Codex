package com.theoriacodex.app.search

import kotlinx.coroutines.awaitCancellation
import com.theoriacodex.app.media.reconnections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
internal class AdmissionFailureSearchViewModelTest : SearchViewModelTestFixture() {
    @Test
    fun `mismatched execution key rejects current result clears loading and never persists`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryRepository = InMemoryQueryRepository()
            lateinit var mismatch: MismatchedExecutionService
            val viewModel = viewModel(
                adapter = ViewModelSearchAdapter(),
                queryRepository = queryRepository,
                executionService = { coordinator -> MismatchedExecutionService(coordinator).also { mismatch = it } },
            )
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("mismatch")))

            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.loading)
            assertEquals(null, viewModel.state.value.execution.activeRequestId)
            assertTrue(viewModel.state.value.content.results.isEmpty())
            assertEquals(0, mismatch.persistCalls)
            assertEquals(null, queryRepository.observeAppliedQuery("source:PIXIV").first())
        }

    @Test
    fun `superseded noncancellable success cannot persist history`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryRepository = InMemoryQueryRepository()
            val recents = InMemoryRecentsRepository()
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter, queryRepository = queryRepository, recentsRepository = recents)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("slow")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            adapter.slowSearchStarted.await()

            viewModel.onAction(SearchAction.ClearDraft)
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            adapter.releaseSlowSearch.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("fast-result"), resultIds(viewModel))
            assertEquals(listOf("fast"), queryRepository.observeAppliedQuery("source:PIXIV").first()?.includeTags)
            assertEquals(listOf(listOf("fast")), recents.observeSearches().first().map { it.query.includeTags })
        }

    @Test
    fun `a failed search retries by itself when the network returns`() = runTest(mainDispatcherRule.dispatcher) {
        val network = MutableStateFlow(false)
        val adapter = ViewModelSearchAdapter()
        val viewModel = viewModel(adapter, reconnections = network.take(2).reconnections())
        restore(viewModel)
        viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        adapter.failingTags += "offline"
        viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("offline")))
        viewModel.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.content.error != null)
        val attempts = adapter.searchCount

        adapter.failingTags.clear()
        network.value = true
        advanceUntilIdle()

        assertEquals(attempts + 1, adapter.searchCount)
        assertEquals(null, viewModel.state.value.content.error)
        assertTrue(resultIds(viewModel).isNotEmpty())
    }

    @Test
    fun `a request still failing when the network returns gets the retry once it ends`() = runTest(mainDispatcherRule.dispatcher) {
        val network = MutableStateFlow(false)
        val adapter = ViewModelSearchAdapter()
        val firstAttempt = CompletableDeferred<Unit>()
        var attempts = 0
        val viewModel = viewModel(adapter, reconnections = network.take(2).reconnections(), executionService = { coordinator ->
            object : SearchExecutionService by coordinator {
                override suspend fun executeInitialProgressively(
                    query: Query,
                    sourceScope: SearchSourceScope,
                    onPartial: suspend (SearchExecutionResult.Success) -> Unit,
                ): SearchExecutionResult = executeInitial(query, sourceScope)

                override suspend fun executeInitial(query: Query, sourceScope: SearchSourceScope): SearchExecutionResult {
                    if ("offline" !in query.includeTags || ++attempts > 1) return coordinator.executeInitial(query, sourceScope)
                    firstAttempt.await()
                    return coordinator.executeInitial(query, sourceScope).also { adapter.failingTags.clear() }
                }
            }
        })
        restore(viewModel)
        viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        adapter.failingTags += "offline"
        viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("offline")))
        viewModel.onAction(SearchAction.ApplyDraft)
        // Not advanceUntilIdle: that would jump past the root timeout and end the gated request.
        runCurrent()

        network.value = true
        runCurrent()
        assertEquals(1, attempts)
        firstAttempt.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(null, viewModel.state.value.content.error)
        assertTrue(resultIds(viewModel).isNotEmpty())
    }

    @Test
    fun `a search that times out across a reconnect retries itself, not the search before it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val network = MutableStateFlow(false)
            val adapter = ViewModelSearchAdapter()
            var hangSecond = true
            val viewModel = viewModel(adapter, rootRequestTimeoutMs = 100L, reconnections = network.take(2).reconnections(),
                executionService = { coordinator ->
                    object : SearchExecutionService by coordinator {
                        override suspend fun executeInitialProgressively(
                            query: Query,
                            sourceScope: SearchSourceScope,
                            onPartial: suspend (SearchExecutionResult.Success) -> Unit,
                        ): SearchExecutionResult = executeInitial(query, sourceScope)

                        override suspend fun executeInitial(query: Query, sourceScope: SearchSourceScope): SearchExecutionResult {
                            if ("second" in query.includeTags && hangSecond) awaitCancellation()
                            return coordinator.executeInitial(query, sourceScope)
                        }
                    }
                })
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("first")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            viewModel.onAction(SearchAction.ClearDraft)
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("second")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()

            network.value = true
            runCurrent()
            hangSecond = false
            adapter.searchedTags.clear()
            advanceTimeBy(101L)
            runCurrent()

            assertEquals(listOf("second"), adapter.searchedTags)
            assertEquals(listOf("second"), viewModel.state.value.query.applied.includeTags)
            assertEquals(null, viewModel.state.value.content.error)
        }

    @Test
    fun `admitted root failure applies failed query disables paging persists once and retry targets it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryRepository = InMemoryQueryRepository()
            val recents = InMemoryRecentsRepository()
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter, queryRepository = queryRepository, recentsRepository = recents)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("paged")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.content.canLoadMore)

            adapter.failingTags += "broken"
            viewModel.onAction(SearchAction.ClearDraft)
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("broken")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()

            val failed = viewModel.state.value
            assertEquals(listOf("broken"), failed.query.applied.includeTags)
            assertTrue(resultIds(viewModel).isEmpty())
            assertFalse(failed.content.canLoadMore)
            assertTrue(failed.content.error?.message.orEmpty().contains("broken"))
            assertEquals(null, failed.execution.activeRequestId)
            assertEquals(listOf("broken"), queryRepository.observeAppliedQuery("source:PIXIV").first()?.includeTags)
            assertEquals(2, recents.observeSearches().first().size)

            adapter.failingTags.clear()
            viewModel.onAction(SearchAction.Retry)
            advanceUntilIdle()

            assertEquals(listOf("broken-result"), resultIds(viewModel))
            assertEquals("broken", adapter.searchedTags.last())
            assertEquals(2, recents.observeSearches().first().size)
        }

}
