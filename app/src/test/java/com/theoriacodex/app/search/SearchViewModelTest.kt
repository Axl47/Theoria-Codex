package com.theoriacodex.app.search

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchRestorationUiState
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
import kotlinx.coroutines.CompletableDeferred
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

    private suspend fun kotlinx.coroutines.test.TestScope.restore(viewModel: SearchViewModel) {
        viewModel.onAction(SearchAction.Restore)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.restoration is SearchRestorationUiState.Restored)
    }

    private fun viewModel(
        adapter: ViewModelSearchAdapter,
        savedState: SavedStateHandle = SavedStateHandle(),
        autocompleteDelayMs: Long = 0L,
    ): SearchViewModel {
        return SearchViewModel(
            coordinator = SearchCoordinator(ViewModelSearchRegistry(adapter)),
            savedStateHandle = savedState,
            autocompleteDelayMs = autocompleteDelayMs,
            scrollPersistenceDelayMs = 0L,
        )
    }

    private fun resultIds(viewModel: SearchViewModel): List<String> {
        return viewModel.state.value.content.results.map { it.id.sourcePostId }
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
    private val adapter: SourceAdapter,
) : SourceAdapterRegistry {
    private val orchestrator = UnifiedSearchOrchestrator(mapOf(adapter.sourceKey to adapter))

    override fun availableSources(): Set<SourceKey> = setOf(adapter.sourceKey)

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        return adapter.takeIf { sourceKey == adapter.sourceKey }
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
