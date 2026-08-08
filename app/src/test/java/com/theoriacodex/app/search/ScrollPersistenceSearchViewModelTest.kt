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
internal class ScrollPersistenceSearchViewModelTest : SearchViewModelTestFixture() {
    @Test
    fun `scroll bursts coalesce and superseded debounce jobs never write stale positions`() =
        runTest(mainDispatcherRule.dispatcher) {
            val uiRestore = RecordingUiRestoreRepository()
            val viewModel = viewModel(
                adapter = ViewModelSearchAdapter(),
                uiRestoreRepository = uiRestore,
                scrollPersistenceDelayMs = 100L,
                scrollPersistenceDispatcher = mainDispatcherRule.dispatcher,
            )

            viewModel.onAction(SearchAction.ScrollChanged(1, 10))
            advanceTimeBy(40L)
            viewModel.onAction(SearchAction.ScrollChanged(2, 20))
            advanceTimeBy(40L)
            viewModel.onAction(SearchAction.ScrollChanged(3, 30))
            advanceTimeBy(99L)
            runCurrent()

            assertTrue(uiRestore.writeSnapshot().isEmpty())
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(SearchScrollState(3, 30)), uiRestore.writeSnapshot().map { it.second })
        }

    @Test
    fun `pending durable scroll keeps its query key while temporary scroll creates no target`() =
        runTest(mainDispatcherRule.dispatcher) {
            val uiRestore = RecordingUiRestoreRepository()
            val owner = viewModel(
                adapter = ViewModelSearchAdapter(),
                additionalAdapters = listOf(ViewModelSearchAdapter(SourceKey.GELBOORU)),
                uiRestoreRepository = uiRestore,
                scrollPersistenceDelayMs = 10_000L,
                scrollPersistenceDispatcher = mainDispatcherRule.dispatcher,
            )
            restore(owner)
            owner.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            owner.onAction(SearchAction.ApplyDraft)
            runCurrent()
            uiRestore.clearWrites()
            val durableQueryHash = owner.state.value.query.appliedQueryHash
            val store = ViewModelStore()
            val provider = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = owner as T
                },
            )
            provider[SearchViewModel::class.java]

            owner.onAction(SearchAction.ScrollChanged(4, 40))
            owner.onAction(SearchAction.ToggleTemporarySource(SourceKey.GELBOORU))
            owner.onAction(SearchAction.ApplyDraft)
            runCurrent()
            owner.onAction(SearchAction.ScrollChanged(5, 50))
            runCurrent()
            store.clear()

            assertEquals(
                listOf(durableQueryHash to SearchScrollState(4, 40)),
                uiRestore.writeSnapshot(),
            )
        }

    @Test
    fun `clearing the navigation owner flushes its final pending scroll position`() =
        runTest(mainDispatcherRule.dispatcher) {
            val uiRestore = RecordingUiRestoreRepository()
            val owner = viewModel(
                adapter = ViewModelSearchAdapter(),
                uiRestoreRepository = uiRestore,
                scrollPersistenceDelayMs = 10_000L,
                scrollPersistenceDispatcher = mainDispatcherRule.dispatcher,
            )
            val store = ViewModelStore()
            val provider = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = owner as T
                },
            )
            provider[SearchViewModel::class.java]
            owner.onAction(SearchAction.ScrollChanged(8, 80))
            runCurrent()

            store.clear()

            assertEquals(listOf(SearchScrollState(8, 80)), uiRestore.writeSnapshot().map { it.second })
            owner.onAction(SearchAction.ScrollChanged(9, 90))
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(listOf(SearchScrollState(8, 80)), uiRestore.writeSnapshot().map { it.second })
        }

    @Test
    fun `clear joins one begun durable write and returns only after acknowledgement`() =
        runTest(mainDispatcherRule.dispatcher) {
            val uiRestore = BlockingUiRestoreRepository()
            val owner = viewModel(
                adapter = ViewModelSearchAdapter(),
                uiRestoreRepository = uiRestore,
                scrollPersistenceDispatcher = Dispatchers.IO,
            )
            val store = ViewModelStore()
            val provider = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = owner as T
                },
            )
            provider[SearchViewModel::class.java]

            owner.onAction(SearchAction.ScrollChanged(1, 10))
            uiRestore.firstWriteStarted.await()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val clear = executor.submit<Unit> { store.clear() }

                assertThrows(TimeoutException::class.java) {
                    clear.get(100L, TimeUnit.MILLISECONDS)
                }
                uiRestore.releaseFirstWrite.complete(Unit)
                clear.get(5L, TimeUnit.SECONDS)

                assertEquals(
                    listOf(SearchScrollState(1, 10)),
                    uiRestore.writeSnapshot().map { it.second },
                )
            } finally {
                uiRestore.releaseFirstWrite.complete(Unit)
                executor.shutdownNow()
            }
        }

    @Test
    fun `restoration is published once and page append does not replay it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryRepository = InMemoryQueryRepository()
            val uiRestore = InMemoryUiRestoreRepository()
            val first = viewModel(
                adapter = ViewModelSearchAdapter(),
                queryRepository = queryRepository,
                uiRestoreRepository = uiRestore,
                scrollPersistenceDispatcher = mainDispatcherRule.dispatcher,
            )
            restore(first)
            first.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            first.onAction(SearchAction.AddIncludeTerm(SearchTerm("paged")))
            first.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            val queryHash = first.state.value.query.appliedQueryHash
            uiRestore.setSearchScrollState(queryHash, SearchScrollState(4, 19))

            val recreated = viewModel(
                adapter = ViewModelSearchAdapter(),
                queryRepository = queryRepository,
                uiRestoreRepository = uiRestore,
                scrollPersistenceDispatcher = mainDispatcherRule.dispatcher,
            )
            restore(recreated)
            val restored = recreated.state.value.restoration
            assertEquals(
                SearchRestorationUiState.Restored(
                    restoredQuery = false,
                    scrollState = SearchScrollState(4, 19),
                    scrollRequestId = 1L,
                ),
                restored,
            )

            recreated.onAction(SearchAction.LoadNextPage)
            advanceUntilIdle()

            assertEquals(restored, recreated.state.value.restoration)
            assertEquals(listOf("paged-result", "paged-page"), resultIds(recreated))
        }

    @Test
    fun `route reentry republishes the latest saved scroll position exactly once`() =
        runTest(mainDispatcherRule.dispatcher) {
            val owner = viewModel(
                adapter = ViewModelSearchAdapter(),
                scrollPersistenceDispatcher = mainDispatcherRule.dispatcher,
            )
            restore(owner)
            owner.onAction(SearchAction.ScrollChanged(6, 32))

            owner.onAction(SearchAction.Resume)

            val requested = owner.state.value.restoration as SearchRestorationUiState.Restored
            assertEquals(SearchScrollState(6, 32), requested.scrollState)
            assertEquals(1L, requested.scrollRequestId)

            owner.onAction(SearchAction.ScrollRestorationApplied(requested.scrollRequestId))

            assertEquals(
                SearchRestorationUiState.Restored(
                    restoredQuery = false,
                    scrollState = null,
                    scrollRequestId = 1L,
                ),
                owner.state.value.restoration,
            )
        }

}
