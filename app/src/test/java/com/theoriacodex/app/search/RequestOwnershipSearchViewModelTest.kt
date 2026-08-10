package com.theoriacodex.app.search

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
import com.theoriacodex.app.testing.TestAnimatedDurationEnricher
import com.theoriacodex.app.testing.animatedTestPost
import com.theoriacodex.app.media.AnimatedDurationEnricher
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
internal class RequestOwnershipSearchViewModelTest : SearchViewModelTestFixture() {
    @Test
    fun `new search cancels delayed work and stale completion cannot replace current state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("slow")))

            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            adapter.slowSearchStarted.await()
            val cancelledRequest = viewModel.state.value.execution.activeRequestId

            viewModel.onAction(SearchAction.ClearDraft)
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()

            assertEquals(listOf("fast-result"), resultIds(viewModel))
            assertEquals(cancelledRequest, viewModel.state.value.execution.lastCancelledRequestId)

            adapter.releaseSlowSearch.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("fast-result"), resultIds(viewModel))
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun `autocomplete debounce publishes only the latest generation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter, autocompleteDelayMs = 100L)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))

            viewModel.onAction(SearchAction.AutocompleteChanged("old"))
            advanceTimeBy(100L)
            runCurrent()
            adapter.oldAutocompleteStarted.await()

            viewModel.onAction(SearchAction.AutocompleteChanged("new"))
            advanceTimeBy(100L)
            runCurrent()

            assertEquals("new", viewModel.state.value.suggestions.input)
            assertEquals(
                listOf("new-suggestion"),
                viewModel.state.value.suggestions.autocomplete.map(TagSuggestion::text),
            )

            adapter.releaseOldAutocomplete.complete(Unit)
            advanceUntilIdle()

            assertEquals("new", viewModel.state.value.suggestions.input)
            assertEquals(
                listOf("new-suggestion"),
                viewModel.state.value.suggestions.autocomplete.map(TagSuggestion::text),
            )
        }

    @Test
    fun `cancel action clears active request and rejects its late result`() =
        runTest(mainDispatcherRule.dispatcher) {
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("slow")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            adapter.slowSearchStarted.await()
            val requestId = viewModel.state.value.execution.activeRequestId

            viewModel.onAction(SearchAction.CancelActiveRequest)

            assertFalse(viewModel.state.value.loading)
            assertEquals(requestId, viewModel.state.value.execution.lastCancelledRequestId)

            adapter.releaseSlowSearch.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.content.results.isEmpty())
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun `temporary source action updates draft scope and pending state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(
                adapter = ViewModelSearchAdapter(SourceKey.PIXIV),
                additionalAdapters = listOf(ViewModelSearchAdapter(SourceKey.GELBOORU)),
            )
            restore(viewModel)

            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.ToggleTemporarySource(SourceKey.GELBOORU))
            advanceUntilIdle()

            val query = viewModel.state.value.query
            assertEquals(
                SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
                query.draftSourceScope,
            )
            assertEquals(SearchSourceScope.GlobalUnified, query.appliedSourceScope)
            assertEquals(QueryMode.Unified, query.draft.mode)
            assertTrue(viewModel.state.value.hasPendingChanges)
        }

    @Test
    fun `duration enrichment copies into current search results`() =
        runTest(mainDispatcherRule.dispatcher) {
            val post = animatedTestPost(sourcePostId = "animated-result")
            val adapter = ViewModelSearchAdapter().apply {
                resultFactory = { listOf(post) }
            }
            val enricher = TestAnimatedDurationEnricher { 4_500L }
            val viewModel = viewModel(adapter, animatedDurationEnricher = enricher)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("animated")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            val queryHash = requireNotNull(viewModel.state.value.query.appliedQueryHash)

            viewModel.onAction(SearchAction.RequestAnimatedDurationEnrichment(queryHash))
            advanceUntilIdle()

            assertEquals(4_500L, viewModel.state.value.content.results.single().durationMs)
            assertEquals(null, post.durationMs)
        }

    @Test
    fun `duration completion from replaced query cannot update fresh results`() =
        runTest(mainDispatcherRule.dispatcher) {
            val oldPost = animatedTestPost(sourcePostId = "shared", title = "old")
            val freshPost = animatedTestPost(sourcePostId = "shared", title = "fresh")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val adapter = ViewModelSearchAdapter().apply {
                resultFactory = { tag -> listOf(if (tag == "old") oldPost else freshPost) }
            }
            val enricher = TestAnimatedDurationEnricher {
                started.complete(Unit)
                release.await()
                7_000L
            }
            val viewModel = viewModel(adapter, animatedDurationEnricher = enricher)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("old")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            val oldQueryHash = requireNotNull(viewModel.state.value.query.appliedQueryHash)
            viewModel.onAction(SearchAction.RequestAnimatedDurationEnrichment(oldQueryHash))
            runCurrent()
            started.await()

            viewModel.onAction(SearchAction.ClearDraft)
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("fresh")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals("fresh", viewModel.state.value.content.results.single().title)
            assertEquals(null, viewModel.state.value.content.results.single().durationMs)
        }

    @Test
    fun `paging is owned by the view model and appends one typed request`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(ViewModelSearchAdapter())
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("paged")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.content.canLoadMore)
            viewModel.onAction(SearchAction.LoadNextPage)
            advanceUntilIdle()

            assertEquals(listOf("paged-result", "paged-page"), resultIds(viewModel))
            assertFalse(viewModel.state.value.content.canLoadMore)
            assertFalse(viewModel.state.value.loadingMore)
        }

    @Test
    fun `stale noncancellable page cannot enter a replacement execution`() =
        runTest(mainDispatcherRule.dispatcher) {
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("slow-page")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()

            viewModel.onAction(SearchAction.LoadNextPage)
            runCurrent()
            adapter.slowPageStarted.await()
            viewModel.onAction(SearchAction.ClearDraft)
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("replacement")))
            viewModel.onAction(SearchAction.ApplyDraft)
            runCurrent()
            adapter.releaseSlowPage.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("replacement-result"), resultIds(viewModel))
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun `external history and codex tag searches remain owner scheduled`() =
        runTest(mainDispatcherRule.dispatcher) {
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter)
            restore(viewModel)

            viewModel.onAction(
                SearchAction.ApplyHistoricalQuery(
                    Query(
                        mode = QueryMode.Source(SourceKey.PIXIV),
                        includeTerms = listOf(SearchTerm("history")),
                        excludeTerms = emptyList(),
                        sort = SortMode.NEWEST,
                        dateRange = null,
                        minScore = null,
                    )
                )
            )
            advanceUntilIdle()
            assertEquals(listOf("history-result"), resultIds(viewModel))

            viewModel.onAction(
                SearchAction.ApplyTagSearch(
                    includeTags = listOf("codex"),
                    mode = QueryMode.Source(SourceKey.PIXIV),
                )
            )
            advanceUntilIdle()

            assertEquals(2, adapter.searchCount)
            assertEquals(listOf("codex-result"), resultIds(viewModel))
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun `historical Multi-Search restores its explicit source scope`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(
                adapter = ViewModelSearchAdapter(SourceKey.PIXIV),
                additionalAdapters = listOf(ViewModelSearchAdapter(SourceKey.GELBOORU)),
            )
            restore(viewModel)
            val scope = SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV))

            viewModel.onAction(
                SearchAction.ApplyHistoricalQuery(
                    query = Query(
                        mode = QueryMode.Unified,
                        includeTerms = listOf(SearchTerm("history")),
                        excludeTerms = emptyList(),
                        sort = SortMode.NEWEST,
                        dateRange = null,
                        minScore = null,
                    ),
                    sourceScope = scope,
                )
            )
            advanceUntilIdle()

            assertEquals(scope, viewModel.state.value.query.appliedSourceScope)
            assertEquals(2, viewModel.state.value.content.results.size)
        }

    @Test
    fun `environment reconciliation schedules retry through the owner`() =
        runTest(mainDispatcherRule.dispatcher) {
            val adapter = ViewModelSearchAdapter()
            val viewModel = viewModel(adapter)
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            assertEquals(1, adapter.searchCount)

            viewModel.synchronizeEnvironment(
                AppSettings(
                    runtime = AppSettings().runtime.copy(
                        enabledSources = setOf(SourceKey.PIXIV),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(2, adapter.searchCount)
            assertFalse(viewModel.state.value.loading)
            assertEquals(listOf("fast-result"), resultIds(viewModel))
        }

}
