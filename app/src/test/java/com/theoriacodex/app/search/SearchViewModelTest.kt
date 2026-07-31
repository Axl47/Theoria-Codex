package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
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
class SearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = SearchMainDispatcherRule()

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

    @Test
    fun `open result emits buffered typed navigation effect`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(ViewModelSearchAdapter())
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            viewModel.onAction(SearchAction.AddIncludeTerm(SearchTerm("fast")))
            viewModel.onAction(SearchAction.ApplyDraft)
            advanceUntilIdle()
            val post = viewModel.state.value.content.results.single()

            viewModel.onAction(
                SearchAction.OpenResult(
                    postId = post.id,
                    visibleResults = listOf(post),
                    scrollOffsetHint = 27,
                ),
            )
            runCurrent()

            val effect = viewModel.effects.first() as SearchEffect.OpenViewer
            assertEquals(listOf(post), effect.posts)
            assertEquals(0, effect.context.startIndex)
            assertEquals(27, effect.context.scrollOffsetHint)
        }

    @Test
    fun `direct nhentai id emits a static viewer effect from owner work`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel(ViewModelSearchAdapter(SourceKey.NHENTAI))
            restore(viewModel)
            viewModel.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.NHENTAI)))
            viewModel.onAction(SearchAction.CommitTagInput("123"))
            advanceUntilIdle()

            val effect = viewModel.effects.first() as SearchEffect.OpenViewer
            assertEquals("123", effect.posts.single().id.sourcePostId)
            assertEquals("nhentai-search-id:123", effect.context.queryHash)
            assertFalse(effect.liveSearchBinding)
        }

    @Test
    fun `saved state restores compact draft and scroll reconstruction input`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val first = viewModel(ViewModelSearchAdapter(), savedState = savedState)
            restore(first)
            val range = DateRange(fromEpochMs = 100L, toEpochMs = 200L)
            first.onAction(SearchAction.SelectMode(QueryMode.Source(SourceKey.PIXIV)))
            first.onAction(SearchAction.AddIncludeTerm(SearchTerm("restored tag")))
            first.onAction(SearchAction.SelectSort(SortMode.POPULAR))
            first.onAction(SearchAction.SetDateRange(range))
            first.onAction(SearchAction.SetMinimumScore(42))
            first.onAction(SearchAction.ScrollChanged(4, 19))
            runCurrent()

            assertNotNull(savedState.get<String>(SearchSavedStateKeys.DRAFT_QUERY))
            assertEquals(true, savedState.get<Boolean>(SearchSavedStateKeys.RESTORATION_COMPLETED))

            val recreated = viewModel(ViewModelSearchAdapter(), savedState = savedState)
            recreated.onAction(SearchAction.Restore)
            advanceUntilIdle()

            val restored = recreated.state.value
            assertEquals(QueryMode.Source(SourceKey.PIXIV), restored.query.draft.mode)
            assertEquals(listOf("restored tag"), restored.query.draft.includeTags)
            assertEquals(SortMode.POPULAR, restored.query.draft.sort)
            assertEquals(range, restored.query.draft.dateRange)
            assertEquals(42, restored.query.draft.minScore)
            assertEquals(
                SearchRestorationUiState.Restored(
                    restoredQuery = true,
                    scrollState = com.theoriacodex.data.repository.SearchScrollState(4, 19),
                ),
                restored.restoration,
            )
        }

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
                SearchRestorationUiState.Restored(false, SearchScrollState(4, 19)),
                restored,
            )

            recreated.onAction(SearchAction.LoadNextPage)
            advanceUntilIdle()

            assertEquals(restored, recreated.state.value.restoration)
            assertEquals(listOf("paged-result", "paged-page"), resultIds(recreated))
        }

    private suspend fun kotlinx.coroutines.test.TestScope.restore(viewModel: SearchViewModel) {
        viewModel.onAction(SearchAction.Restore)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.restoration is SearchRestorationUiState.Restored)
    }

    private fun viewModel(
        adapter: ViewModelSearchAdapter,
        savedState: SavedStateHandle = SavedStateHandle(),
        autocompleteDelayMs: Long = 0L,
        additionalAdapters: List<SourceAdapter> = emptyList(),
        queryRepository: QueryRepository = InMemoryQueryRepository(),
        uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
        scrollPersistenceDelayMs: Long = 0L,
        scrollPersistenceDispatcher: CoroutineDispatcher = mainDispatcherRule.dispatcher,
    ): SearchViewModel {
        return SearchViewModel(
            coordinator = SearchCoordinator(
                ViewModelSearchRegistry(adapter, *additionalAdapters.toTypedArray()),
                queryRepository = queryRepository,
                settingsRepository = InMemorySettingsRepository(),
                uiRestoreRepository = uiRestoreRepository,
            ),
            savedStateHandle = savedState,
            autocompleteDelayMs = autocompleteDelayMs,
            scrollPersistenceDelayMs = scrollPersistenceDelayMs,
            scrollPersistenceDispatcher = scrollPersistenceDispatcher,
        )
    }

    private fun resultIds(viewModel: SearchViewModel): List<String> {
        return viewModel.state.value.content.results.map { it.id.sourcePostId }
    }
}

private open class RecordingUiRestoreRepository(
    private val delegate: InMemoryUiRestoreRepository = InMemoryUiRestoreRepository(),
) : UiRestoreRepository by delegate {
    protected val writes: MutableList<Pair<String, SearchScrollState>> =
        Collections.synchronizedList(mutableListOf())

    fun writeSnapshot(): List<Pair<String, SearchScrollState>> = synchronized(writes) {
        writes.toList()
    }

    fun clearWrites() = synchronized(writes) { writes.clear() }

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        delegate.setSearchScrollState(queryHash, state)
        writes += queryHash to state
    }
}

private class BlockingUiRestoreRepository : RecordingUiRestoreRepository() {
    val firstWriteStarted = CompletableDeferred<Unit>()
    val releaseFirstWrite = CompletableDeferred<Unit>()
    private var shouldBlock = true

    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        if (shouldBlock) {
            shouldBlock = false
            firstWriteStarted.complete(Unit)
            releaseFirstWrite.await()
        }
        super.setSearchScrollState(queryHash, state)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchMainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class ViewModelSearchRegistry(
    adapter: SourceAdapter,
    vararg additionalAdapters: SourceAdapter,
) : SourceAdapterRegistry {
    private val adapters = listOf(adapter) + additionalAdapters
    private val orchestrator = UnifiedSearchOrchestrator(
        adapters.associateBy(SourceAdapter::sourceKey),
    )

    override fun availableSources(): Set<SourceKey> = adapters.mapTo(mutableSetOf(), SourceAdapter::sourceKey)

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        return adapters.firstOrNull { adapter -> adapter.sourceKey == sourceKey }
    }

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
}

private class ViewModelSearchAdapter(
    override val sourceKey: SourceKey = SourceKey.PIXIV,
) : SourceAdapter {
    override val capabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = true,
        supportsMinScoreServerSide = true,
        requiresCredentials = false,
    )

    val slowSearchStarted = CompletableDeferred<Unit>()
    val releaseSlowSearch = CompletableDeferred<Unit>()
    val oldAutocompleteStarted = CompletableDeferred<Unit>()
    val releaseOldAutocomplete = CompletableDeferred<Unit>()
    var searchCount: Int = 0

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        searchCount += 1
        val tag = query.includeTags.firstOrNull().orEmpty()
        if (tag == "slow") {
            slowSearchStarted.complete(Unit)
            withContext(NonCancellable) { releaseSlowSearch.await() }
            return Page(listOf(post("slow-result")), null)
        }
        if (pageToken == "next") return Page(listOf(post("paged-page")), null)
        return Page(
            items = listOf(post("$tag-result")),
            nextPageToken = "next".takeIf { tag == "paged" },
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        if (prefix == "old") {
            oldAutocompleteStarted.complete(Unit)
            withContext(NonCancellable) { releaseOldAutocomplete.await() }
            return listOf(TagSuggestion("old-suggestion", "tag", null))
        }
        return listOf(TagSuggestion("$prefix-suggestion", "tag", null))
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query {
        return Query(
            mode = QueryMode.Source(sourceKey),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    override suspend fun resolvePost(id: PostId): Post? {
        return post(id.sourcePostId).takeIf { id.source == sourceKey && sourceKey == SourceKey.NHENTAI }
    }

    private fun post(id: String): Post {
        return Post(
            id = PostId(sourceKey, id),
            preview = ImageRef(
                url = "https://example.test/$id.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = null,
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }
}
