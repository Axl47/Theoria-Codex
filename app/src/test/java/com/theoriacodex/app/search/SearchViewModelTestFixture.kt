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
internal abstract class SearchViewModelTestFixture {
    @get:Rule
    val mainDispatcherRule = SearchMainDispatcherRule()

    protected fun viewModel(
        adapter: ViewModelSearchAdapter,
        savedState: SavedStateHandle = SavedStateHandle(),
        autocompleteDelayMs: Long = 0L,
        additionalAdapters: List<SourceAdapter> = emptyList(),
        queryRepository: QueryRepository = InMemoryQueryRepository(),
        uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
        scrollPersistenceDelayMs: Long = 0L,
        scrollPersistenceDispatcher: CoroutineDispatcher = mainDispatcherRule.dispatcher,
        recentsRepository: RecentsRepository = InMemoryRecentsRepository(),
        executionService: ((SearchCoordinator) -> SearchExecutionService)? = null,
    ): SearchViewModel {
        val coordinator = testSearchCoordinator(
            ViewModelSearchRegistry(adapter, *additionalAdapters.toTypedArray()),
            queryRepository = queryRepository,
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = uiRestoreRepository,
            recentsRepository = recentsRepository,
        )
        return SearchViewModel(
            coordinator = coordinator,
            savedStateHandle = savedState,
            executionService = executionService?.invoke(coordinator) ?: coordinator,
            autocompleteDelayMs = autocompleteDelayMs,
            scrollPersistenceDelayMs = scrollPersistenceDelayMs,
            scrollPersistenceDispatcher = scrollPersistenceDispatcher,
        )
    }

    protected fun resultIds(viewModel: SearchViewModel): List<String> {
        return viewModel.state.value.content.results.map { it.id.sourcePostId }
    }

    protected suspend fun kotlinx.coroutines.test.TestScope.restore(viewModel: SearchViewModel) {
        viewModel.onAction(SearchAction.Restore)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.restoration is SearchRestorationUiState.Restored)
    }
}

internal open class RecordingUiRestoreRepository(
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

internal class BlockingUiRestoreRepository : RecordingUiRestoreRepository() {
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

internal class MismatchedExecutionService(
    private val delegate: SearchExecutionService,
) : SearchExecutionService by delegate {
    var persistCalls = 0

    override suspend fun executeInitial(
        query: Query,
        sourceScope: SearchSourceScope,
    ): SearchExecutionResult {
        return when (val result = delegate.executeInitial(query, sourceScope)) {
            is SearchExecutionResult.Failure -> result.copy(executionKey = "mismatched-key")
            is SearchExecutionResult.Success -> result.copy(
                executionKey = "mismatched-key",
                continuation = result.continuation.copy(executionKey = "mismatched-key"),
            )
        }
    }

    override suspend fun persistAppliedSearch(
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
    ) {
        persistCalls += 1
        delegate.persistAppliedSearch(query, sourceScope, executionKey)
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

internal class ViewModelSearchRegistry(
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

internal class ViewModelSearchAdapter(
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
    val slowPageStarted = CompletableDeferred<Unit>()
    val releaseSlowPage = CompletableDeferred<Unit>()
    var searchCount: Int = 0
    var resultFactory: ((String) -> List<Post>)? = null
    val failingTags = mutableSetOf<String>()
    val searchedTags = mutableListOf<String>()

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        searchCount += 1
        val tag = query.includeTags.firstOrNull().orEmpty()
        searchedTags += tag
        if (tag in failingTags) throw IllegalStateException("$tag unavailable")
        if (tag == "slow") {
            slowSearchStarted.complete(Unit)
            withContext(NonCancellable) { releaseSlowSearch.await() }
            return Page(listOf(post("slow-result")), null)
        }
        if (pageToken == "next") {
            if (tag == "slow-page") {
                slowPageStarted.complete(Unit)
                withContext(NonCancellable) { releaseSlowPage.await() }
                return Page(listOf(post("slow-page-stale")), null)
            }
            return Page(listOf(post("paged-page")), null)
        }
        return Page(
            items = resultFactory?.invoke(tag) ?: listOf(post("$tag-result")),
            nextPageToken = "next".takeIf { tag == "paged" || tag == "slow-page" },
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

    protected fun post(id: String): Post {
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
