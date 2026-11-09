package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
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
                override suspend fun recordAcceptedSearch(result: SearchExecutionResult) {
                    historyStarted.complete(Unit)
                    historyRelease.await()
                    coordinator.recordAcceptedSearch(result)
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
    fun `partial results show while late sources finish without enabling paging`() = runTest(mainDispatcherRule.dispatcher) {
        val lateSourcesDone = CompletableDeferred<Unit>()
        val owner = viewModel(ViewModelSearchAdapter(), executionService = { coordinator ->
            object : SearchExecutionService by coordinator {
                override suspend fun executeInitialProgressively(
                    query: Query,
                    sourceScope: SearchSourceScope,
                    onPartial: suspend (SearchExecutionResult.Success) -> Unit,
                ): SearchExecutionResult {
                    val complete = coordinator.executeInitial(query, sourceScope)
                    onPartial(complete as SearchExecutionResult.Success)
                    lateSourcesDone.await()
                    return complete
                }
            }
        })
        restore(owner)
        owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        owner.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
        owner.onAction(SearchAction.ApplyDraft)
        runCurrent()

        assertEquals(listOf("fast-result"), resultIds(owner))
        assertTrue(owner.state.value.loading)
        assertTrue(owner.state.value.execution.showingPartialResults)
        assertFalse(owner.state.value.content.canLoadMore)

        lateSourcesDone.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("fast-result"), resultIds(owner))
        assertFalse(owner.state.value.loading)
        assertFalse(owner.state.value.execution.showingPartialResults)
    }

    @Test
    fun `reopening paints the cached first page and swaps it when fresh results arrive`() = runTest(mainDispatcherRule.dispatcher) {
        val refresh = CompletableDeferred<Unit>()
        val owner = reopenedSearch(refresh)
        // Not restore(): advancing until idle would run the held request into its timeout.
        owner.onAction(SearchAction.Restore)
        runCurrent()

        assertEquals(listOf("cached"), resultIds(owner))
        assertTrue(owner.state.value.loading)

        refresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("fast-result"), resultIds(owner))
    }

    @Test
    fun `someone who scrolled the cached page keeps it and gets fresh posts below`() = runTest(mainDispatcherRule.dispatcher) {
        val refresh = CompletableDeferred<Unit>()
        val owner = reopenedSearch(refresh)
        // Not restore(): advancing until idle would run the held request into its timeout.
        owner.onAction(SearchAction.Restore)
        runCurrent()

        owner.onAction(SearchAction.ScrollChanged(firstVisibleItemIndex = 3, firstVisibleItemOffsetPx = 0))
        refresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("cached", "fast-result"), resultIds(owner))
    }

    /** A search that already ran once, reopened with a remembered first page and a network answer held by [refresh]. */
    private suspend fun kotlinx.coroutines.test.TestScope.reopenedSearch(refresh: CompletableDeferred<Unit>): SearchViewModel {
        val queries = InMemoryQueryRepository()
        val firstRun = viewModel(ViewModelSearchAdapter(), queryRepository = queries)
        restore(firstRun)
        firstRun.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
        firstRun.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
        firstRun.onAction(SearchAction.ApplyDraft)
        advanceUntilIdle()
        return viewModel(ViewModelSearchAdapter(), queryRepository = queries, executionService = { coordinator ->
            object : SearchExecutionService by coordinator {
                override suspend fun cachedFirstPage(executionKey: String) = listOf(
                    Post(
                        id = PostId(SourceKey.PIXIV, "cached"),
                        preview = ImageRef(url = "https://example.test/cached.jpg", localPath = null, mime = "image/jpeg"),
                        full = null,
                        pageUrl = null,
                        width = 100,
                        height = 100,
                        canonicalTags = emptyList(),
                        rawTags = emptyList(),
                        authorName = null,
                        createdAtEpochMs = null,
                    ),
                )

                override suspend fun executeInitialProgressively(
                    query: Query,
                    sourceScope: SearchSourceScope,
                    onPartial: suspend (SearchExecutionResult.Success) -> Unit,
                ): SearchExecutionResult {
                    refresh.await()
                    return coordinator.executeInitial(query, sourceScope)
                }
            }
        })
    }

    @Test
    fun `same query refresh retains content while a different query does not`() = runTest(mainDispatcherRule.dispatcher) {
        val held = CompletableDeferred<Unit>()
        var hold = false
        val owner = viewModel(ViewModelSearchAdapter(), executionService = { coordinator ->
            object : SearchExecutionService by coordinator {
                override suspend fun executeInitialProgressively(
                    query: Query,
                    sourceScope: SearchSourceScope,
                    onPartial: suspend (SearchExecutionResult.Success) -> Unit,
                ): SearchExecutionResult = executeInitial(query, sourceScope)

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
