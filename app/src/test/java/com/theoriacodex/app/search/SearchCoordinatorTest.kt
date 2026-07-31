package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.query.QueryHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCoordinatorTest {
    @Test
    fun `initial execution returns immutable posts status and continuation`() = runTest {
        val coordinator = coordinator(TestAdapter(SourceKey.PIXIV))
        coordinator.initializeRoute()
        val query = query(SourceKey.PIXIV, "first")

        val result = coordinator.executeInitial(query, SearchSourceScope.Single(SourceKey.PIXIV))
            as SearchExecutionResult.Success

        assertEquals(listOf("first-0"), result.posts.map { it.id.sourcePostId })
        assertEquals(SourceRunState.SUCCESS, result.statuses.single().state)
        assertTrue(result.continuation.canLoadMore)
        assertEquals(QueryHash.from(query), result.executionKey)
    }

    @Test
    fun `unified execution keeps successful posts and typed provider failure`() = runTest {
        val success = TestAdapter(SourceKey.PIXIV)
        val failure = TestAdapter(SourceKey.GELBOORU).apply {
            failure = SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "credentials required")
        }
        val coordinator = coordinator(success, failure)
        coordinator.initializeRoute()

        val result = coordinator.executeInitial(
            query = unifiedQuery("mixed"),
            sourceScope = SearchSourceScope.GlobalUnified,
        ) as SearchExecutionResult.Success

        assertEquals(listOf("mixed-0"), result.posts.map { it.id.sourcePostId })
        assertEquals(SourceRunState.SUCCESS, result.statuses.first { it.source == SourceKey.PIXIV }.state)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, result.statuses.first { it.source == SourceKey.GELBOORU }.failureReason)
    }

    @Test
    fun `all provider failures remain a typed admitted result instead of throwing`() = runTest {
        val adapter = TestAdapter(SourceKey.PIXIV).apply {
            failure = SourceAdapterException(SourceFailureReason.AUTH_REQUIRED, "credentials required")
        }
        val coordinator = coordinator(adapter)
        coordinator.initializeRoute()

        val result = coordinator.executeInitial(
            query = unifiedQuery("failed"),
            sourceScope = SearchSourceScope.GlobalUnified,
        ) as SearchExecutionResult.Success

        assertTrue(result.posts.isEmpty())
        assertEquals(SourceRunState.FAILED, result.statuses.single().state)
        assertFalse(result.continuation.canLoadMore)
    }

    @Test
    fun `source exception returns explicit failure bound to requested execution`() = runTest {
        val adapter = TestAdapter(SourceKey.PIXIV).apply { failure = IllegalStateException("offline") }
        val coordinator = coordinator(adapter)
        coordinator.initializeRoute()
        val query = query(SourceKey.PIXIV, "failed")

        val result = coordinator.executeInitial(query, SearchSourceScope.Single(SourceKey.PIXIV))
            as SearchExecutionResult.Failure

        assertEquals(QueryHash.from(query), result.executionKey)
        assertEquals("offline", result.message)
    }

    @Test
    fun `page result returns only new posts and consumes attempted omitted tokens`() = runTest {
        val adapter = TestAdapter(SourceKey.PIXIV)
        val coordinator = coordinator(adapter)
        coordinator.initializeRoute()
        val query = unifiedQuery("page")
        val key = QueryHash.from(query)
        val continuation = SearchContinuation(
            executionKey = key,
            query = query,
            sourceScope = SearchSourceScope.GlobalUnified,
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            weights = mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5),
            unifiedPageTokens = mapOf(SourceKey.PIXIV to "next", SourceKey.GELBOORU to "next"),
        )

        val result = coordinator.executePage(continuation) as SearchPageResult.Success

        assertEquals(listOf("page-1"), result.posts.map { it.id.sourcePostId })
        assertEquals(null, result.continuation.unifiedPageTokens[SourceKey.PIXIV])
        assertEquals(null, result.continuation.unifiedPageTokens[SourceKey.GELBOORU])
        assertFalse(result.continuation.canLoadMore)
    }

    @Test
    fun `temporary execution key binds canonical source selection`() = runTest {
        val coordinator = coordinator(TestAdapter(SourceKey.PIXIV), TestAdapter(SourceKey.GELBOORU))
        val query = unifiedQuery("temporary")
        val scope = SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV))

        assertEquals(
            "${QueryHash.from(query)}|temporary-sources:GELBOORU,PIXIV",
            coordinator.executionKeyFor(query, scope),
        )
    }

    @Test
    fun `source execution ignores unified settings while unified execution excludes disabled sources`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU)
        val settings = InMemorySettingsRepository().apply { setEnabledSources(setOf(SourceKey.PIXIV)) }
        val coordinator = coordinator(
            settingsRepository = settings,
            adapters = arrayOf(pixiv, gelbooru),
        )
        coordinator.initializeRoute()

        val source = coordinator.executeInitial(
            query(SourceKey.GELBOORU, "source"),
            SearchSourceScope.Single(SourceKey.GELBOORU),
        ) as SearchExecutionResult.Success
        val unified = coordinator.executeInitial(
            unifiedQuery("unified"),
            SearchSourceScope.GlobalUnified,
        ) as SearchExecutionResult.Success

        assertEquals(listOf("source"), gelbooru.searchedTags.first())
        assertEquals(1, source.statuses.size)
        assertEquals(listOf("unified"), pixiv.searchedTags.last())
        assertEquals(1, gelbooru.searchedTags.size)
        assertEquals(SourceRunState.EXCLUDED, unified.statuses.first { it.source == SourceKey.GELBOORU }.state)
    }

    @Test
    fun `temporary execution uses exact sources and persistence remains disabled`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU)
        val hitomi = TestAdapter(SourceKey.HITOMI)
        val queryRepository = InMemoryQueryRepository()
        val recents = InMemoryRecentsRepository()
        val coordinator = coordinator(queryRepository, InMemorySettingsRepository(), InMemoryUiRestoreRepository(), recents, pixiv, gelbooru, hitomi)
        coordinator.initializeRoute()
        val query = unifiedQuery("temporary")
        val scope = SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV))

        val result = coordinator.executeInitial(query, scope) as SearchExecutionResult.Success
        coordinator.persistAppliedSearch(result.query, result.sourceScope, result.executionKey)
        coordinator.executePage(result.continuation)

        assertEquals(2, pixiv.searchedTags.size)
        assertEquals(2, gelbooru.searchedTags.size)
        assertTrue(hitomi.searchedTags.isEmpty())
        assertTrue(recents.observeSearches().first().isEmpty())
        assertEquals(null, queryRepository.observeAppliedQuery("unified").first())
    }

    @Test
    fun `admitted persistence records history once and initialization restores source mode`() = runTest {
        val queryRepository = InMemoryQueryRepository()
        val recents = InMemoryRecentsRepository()
        val restore = InMemoryUiRestoreRepository()
        val adapter = TestAdapter(SourceKey.PIXIV)
        val first = coordinator(queryRepository, InMemorySettingsRepository(), restore, recents, adapter)
        first.initializeRoute()
        val applied = query(SourceKey.PIXIV, "history")
        val result = first.executeInitial(applied, SearchSourceScope.Single(SourceKey.PIXIV)) as SearchExecutionResult.Success

        first.persistAppliedSearch(result.query, result.sourceScope, result.executionKey)
        first.persistAppliedSearch(result.query, result.sourceScope, result.executionKey)
        val restarted = coordinator(queryRepository, InMemorySettingsRepository(), restore, recents, adapter).initializeRoute()

        assertEquals(applied, restarted.query)
        assertTrue(restarted.hasExecutedSearch)
        assertEquals(1, recents.observeSearches().first().size)
    }

    @Test
    fun `scroll writes deduplicate and restoration rejects temporary scope`() = runTest {
        val restore = CountingRestoreRepository()
        val coordinator = coordinator(uiRestoreRepository = restore, adapters = arrayOf(TestAdapter(SourceKey.PIXIV)))
        val key = QueryHash.from(unifiedQuery("scroll"))

        coordinator.persistSearchScrollState(4, 12, key)
        coordinator.persistSearchScrollState(4, 12, key)

        assertEquals(1, restore.writeCount)
        assertEquals(SearchScrollState(4, 12), coordinator.restoreSearchScrollState(key, SearchSourceScope.GlobalUnified))
        assertEquals(
            null,
            coordinator.restoreSearchScrollState(
                key,
                SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
            ),
        )
    }

    @Test
    fun `autocomplete follows temporary sources normalizes prefixes and sorts counts`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV).apply {
            autocomplete = listOf(
                TagSuggestion("blue_hair_low", "tag", 1),
                TagSuggestion("blue_hair_high", "tag", 50),
            )
        }
        val gelbooru = TestAdapter(SourceKey.GELBOORU).apply {
            autocomplete = listOf(TagSuggestion("blue_hair_mid", "tag", 20))
        }
        val hitomi = TestAdapter(SourceKey.HITOMI)
        val coordinator = coordinator(pixiv, gelbooru, hitomi)
        coordinator.initializeRoute()

        val result = coordinator.fetchAutocomplete(
            unifiedQuery(""),
            SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
            com.theoriacodex.domain.adapter.FacetedSearchScope.All,
            "blue hair",
            emptyList(),
        )

        assertEquals("blue hair", pixiv.autocompletePrefixes.single())
        assertEquals("blue_hair", gelbooru.autocompletePrefixes.single())
        assertTrue(hitomi.autocompletePrefixes.isEmpty())
        assertEquals(
            listOf("blue_hair_high", "blue_hair_mid", "blue_hair_low"),
            result.autocomplete.map(TagSuggestion::text),
        )
    }

    @Test
    fun `unified query overrides map provider-compatible tags without promoting source facets`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU).apply {
            autocompleteByPrefix["cat"] = listOf(TagSuggestion("cat_(animal)", "tag", 1))
        }
        val coordinator = coordinator(pixiv, gelbooru)
        coordinator.initializeRoute()
        val query = unifiedQuery("cat").copy(
            includeTerms = listOf(SearchTerm("cat"), SearchTerm("najar", com.theoriacodex.domain.model.SearchFacet.ARTIST, "artist")),
            excludeTerms = listOf(SearchTerm("nsfw_(content)")),
        )

        coordinator.executeInitial(query, SearchSourceScope.GlobalUnified)

        assertEquals(listOf("cat"), pixiv.searchedTags.single())
        assertEquals(listOf("nsfw"), pixiv.searchedExclusions.single())
        assertEquals(listOf("cat_(animal)"), gelbooru.searchedTags.single())
        assertEquals(listOf("nsfw_(content)"), gelbooru.searchedExclusions.single())
    }

    @Test
    fun `resolved post cache is execution scoped and rate limits use bounded retry backoff`() = runTest {
        var now = 1_000L
        val resolved = post(SourceKey.IWARA, "resolved").copy(
            full = ImageRef("https://example.test/resolved.mp4", null, "video/mp4"),
        )
        val adapter = TestAdapter(SourceKey.IWARA).apply { resolvedPost = resolved }
        val coordinator = SearchCoordinator(TestRegistry(listOf(adapter)), clock = { now })
        val key = "query-a"

        assertEquals(resolved, coordinator.resolvePostForSearch(resolved.id, key))
        assertEquals(resolved, coordinator.resolvePostForSearch(resolved.id, key))
        assertEquals(1, adapter.resolveCalls)
        assertEquals(resolved, coordinator.resolvePostForSearch(resolved.id, "query-b"))
        assertEquals(2, adapter.resolveCalls)

        adapter.resolveFailure = SourceAdapterException(SourceFailureReason.RATE_LIMITED, "429")
        val limitedId = PostId(SourceKey.IWARA, "limited")
        assertEquals(null, coordinator.resolvePostForSearch(limitedId, key))
        assertEquals(null, coordinator.resolvePostForSearch(limitedId, key))
        assertEquals(3, adapter.resolveCalls)
        now += 31_000L
        assertEquals(null, coordinator.resolvePostForSearch(limitedId, key))
        assertEquals(4, adapter.resolveCalls)
    }

    @Test
    fun `autocomplete cancellation and pixiv unknown failures preserve typed contracts`() = runTest {
        val cancelling = TestAdapter(SourceKey.PIXIV).apply { autocompleteFailure = CancellationException("changed") }
        val coordinator = coordinator(cancelling)
        coordinator.initializeRoute()
        val cancellation = runCatching {
            coordinator.fetchAutocomplete(
                query(SourceKey.PIXIV, ""),
                SearchSourceScope.Single(SourceKey.PIXIV),
                com.theoriacodex.domain.adapter.FacetedSearchScope.All,
                "tag",
                emptyList(),
            )
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)

        cancelling.autocompleteFailure = null
        cancelling.failure = SourceAdapterException(SourceFailureReason.UNKNOWN, "PIXIV_UNKNOWN")
        val failure = coordinator.executeInitial(
            query(SourceKey.PIXIV, ""),
            SearchSourceScope.Single(SourceKey.PIXIV),
        ) as SearchExecutionResult.Failure
        assertTrue(failure.message.contains("reset", ignoreCase = true))
    }

    @Test
    fun `faceted autocomplete preserves scope identity featured values and cancellation`() = runTest {
        val artist = FacetedSearchScope(SearchFacet.ARTIST, "artist")
        val suggestion = FacetedTagSuggestion("najar", SearchFacet.ARTIST, "artist", 42)
        val adapter = FacetedAdapter(
            source = SourceKey.HITOMI,
            supportedSearchScopes = linkedSetOf(FacetedSearchScope.All, artist),
            autocomplete = listOf(suggestion),
            featured = listOf(suggestion),
        )
        val coordinator = SearchCoordinator(TestRegistry(listOf(adapter)))
        coordinator.initializeRoute()
        val query = query(SourceKey.HITOMI, "")

        val autocomplete = coordinator.fetchAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.HITOMI),
            FacetedSearchScope.All,
            "artist:naj",
            emptyList(),
        )
        val featured = coordinator.fetchAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.HITOMI),
            artist,
            "",
            emptyList(),
        )

        assertEquals(artist, autocomplete.selectedScope)
        assertEquals(listOf(suggestion), autocomplete.facetedAutocomplete)
        assertEquals(listOf(suggestion), featured.facetedAutocomplete)
        adapter.failure = CancellationException("scope changed")
        assertTrue(
            runCatching {
                coordinator.fetchAutocomplete(
                    query,
                    SearchSourceScope.Single(SourceKey.HITOMI),
                    artist,
                    "naj",
                    emptyList(),
                )
            }.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun `tag count lookup caches batch results and seen taxonomy retains namespaces`() = runTest {
        val countDelegate = TestAdapter(SourceKey.GELBOORU)
        val countAdapter = CountAdapter(countDelegate, mapOf("blue_hair" to 321))
        val countStore = RecordingTagStore()
        val counts = SearchCoordinator(TestRegistry(listOf(countAdapter)), tagSuggestionStore = countStore)
        counts.initializeRoute()

        assertEquals(321, counts.fetchTagVideoCounts(SourceKey.GELBOORU, listOf("blue hair"))["blue hair"])
        assertEquals(321, counts.fetchTagVideoCounts(SourceKey.GELBOORU, listOf("blue hair"))["blue hair"])
        assertEquals(1, countAdapter.calls)

        val post = post(SourceKey.NHENTAI, "taxonomy").copy(
            canonicalTags = listOf("shared"),
            taxonomy = listOf(
                PostTaxonomyTerm("shared", SearchFacet.TAG, "female"),
                PostTaxonomyTerm("shared", SearchFacet.TAG, "male"),
            ),
        )
        val taxonomyAdapter = TestAdapter(SourceKey.NHENTAI).apply { fixedPosts = listOf(post) }
        val taxonomyStore = RecordingTagStore()
        val taxonomy = SearchCoordinator(TestRegistry(listOf(taxonomyAdapter)), tagSuggestionStore = taxonomyStore)
        taxonomy.initializeRoute()
        taxonomy.executeInitial(query(SourceKey.NHENTAI, ""), SearchSourceScope.Single(SourceKey.NHENTAI))

        assertEquals(listOf("female", "male"), taxonomyStore.faceted.mapNotNull(FacetedTagSuggestion::sourceNamespace))
    }

    @Test
    fun `temporary trending stays on selected sources and source order is stable`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV).apply { autocomplete = listOf(TagSuggestion("pixiv", "tag", 3)) }
        val gelbooru = TestAdapter(SourceKey.GELBOORU).apply { autocomplete = listOf(TagSuggestion("gelbooru", "tag", 2)) }
        val hitomi = TestAdapter(SourceKey.HITOMI).apply { autocomplete = listOf(TagSuggestion("hitomi", "tag", 1)) }
        val store = RecordingTagStore()
        val coordinator = SearchCoordinator(TestRegistry(listOf(hitomi, gelbooru, pixiv)), tagSuggestionStore = store)
        coordinator.initializeRoute()

        val trending = coordinator.fetchTrending(
            unifiedQuery(""),
            SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
            forceRefresh = true,
        )

        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV, SourceKey.HITOMI), coordinator.availableSources)
        assertEquals(setOf("pixiv", "gelbooru"), trending.mapTo(mutableSetOf(), TagSuggestion::text))
        assertFalse(trending.any { it.text == "hitomi" })
    }

    private fun coordinator(vararg adapters: TestAdapter): SearchCoordinator = coordinator(
        InMemoryQueryRepository(),
        InMemorySettingsRepository(),
        InMemoryUiRestoreRepository(),
        InMemoryRecentsRepository(),
        *adapters,
    )

    private fun coordinator(
        queryRepository: InMemoryQueryRepository = InMemoryQueryRepository(),
        settingsRepository: InMemorySettingsRepository = InMemorySettingsRepository(),
        uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
        recentsRepository: InMemoryRecentsRepository = InMemoryRecentsRepository(),
        vararg adapters: TestAdapter,
    ): SearchCoordinator {
        return SearchCoordinator(
            registry = TestRegistry(adapters.toList()),
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
            recentsRepository = recentsRepository,
        )
    }
}

private class CountingRestoreRepository(
    private val delegate: InMemoryUiRestoreRepository = InMemoryUiRestoreRepository(),
) : UiRestoreRepository by delegate {
    var writeCount = 0
    override suspend fun setSearchScrollState(queryHash: String, state: SearchScrollState) {
        writeCount += 1
        delegate.setSearchScrollState(queryHash, state)
    }
}

private class TestRegistry(adapters: List<SourceAdapter>) : SourceAdapterRegistry {
    private val bySource = adapters.associateBy(SourceAdapter::sourceKey)
    private val orchestrator = UnifiedSearchOrchestrator(bySource)
    override fun availableSources(): Set<SourceKey> = bySource.keys
    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = bySource[sourceKey]
    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
}

private class TestAdapter(override val sourceKey: SourceKey) : SourceAdapter {
    var failure: Throwable? = null
    var autocompleteFailure: Throwable? = null
    var autocomplete: List<TagSuggestion> = emptyList()
    val autocompleteByPrefix = mutableMapOf<String, List<TagSuggestion>>()
    val autocompletePrefixes = mutableListOf<String>()
    val searchedTags = mutableListOf<List<String>>()
    val searchedExclusions = mutableListOf<List<String>>()
    var resolvedPost: Post? = null
    var fixedPosts: List<Post>? = null
    var resolveFailure: Throwable? = null
    var resolveCalls = 0
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

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        failure?.let { throw it }
        searchedTags += query.includeTags
        searchedExclusions += query.excludeTags
        val tag = query.includeTags.firstOrNull().orEmpty()
        val index = if (pageToken == null) 0 else 1
        return Page(
            items = fixedPosts ?: listOf(post(sourceKey, "$tag-$index")),
            nextPageToken = "next".takeIf { pageToken == null },
        )
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = autocomplete.take(limit)

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        autocompleteFailure?.let { throw it }
        autocompletePrefixes += prefix
        return autocompleteByPrefix[prefix]?.take(limit) ?: autocomplete.take(limit)
    }

    override suspend fun quickQuery(kind: QuickQueryKind): Query = query(sourceKey, "")

    override suspend fun resolvePost(id: PostId): Post? {
        resolveCalls += 1
        resolveFailure?.let { throw it }
        return resolvedPost
    }
}

private class FacetedAdapter(
    source: SourceKey,
    override val supportedSearchScopes: Set<FacetedSearchScope>,
    private val autocomplete: List<FacetedTagSuggestion>,
    private val featured: List<FacetedTagSuggestion>,
) : SourceAdapter by TestAdapter(source), FacetedSearchSourceAdapter {
    var failure: Throwable? = null

    override suspend fun autocompleteFaceted(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> {
        failure?.let { throw it }
        return autocomplete.filter { suggestion ->
            suggestion.text.startsWith(prefix, ignoreCase = true) &&
                (scope.isAll || suggestion.facet == scope.facet && suggestion.sourceNamespace == scope.sourceNamespace)
        }.take(limit)
    }

    override suspend fun featuredFacetedSuggestions(
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> = featured.filter { suggestion ->
        suggestion.facet == scope.facet && suggestion.sourceNamespace == scope.sourceNamespace
    }.take(limit)
}

private class CountAdapter(
    delegate: TestAdapter,
    private val counts: Map<String, Int>,
) : SourceAdapter by delegate, TagCountLookupSourceAdapter {
    var calls = 0
    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
        calls += 1
        return counts.filterKeys { it in tags }
    }
}

private class RecordingTagStore : TagSuggestionStore {
    private val legacy = mutableMapOf<SourceKey, MutableList<TagSuggestion>>()
    val faceted = mutableListOf<FacetedTagSuggestion>()

    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> = legacy[source].orEmpty().take(limit)

    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) {
        legacy.getOrPut(source) { mutableListOf() }.apply {
            suggestions.forEach { incoming ->
                removeAll { it.text == incoming.text }
                add(incoming)
            }
        }
    }

    override fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> = faceted.filter { suggestion ->
        scope.isAll || suggestion.facet == scope.facet && suggestion.sourceNamespace == scope.sourceNamespace
    }.take(limit)

    override fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) {
        faceted += suggestions
    }
}

private fun query(source: SourceKey, tag: String) = Query(
    mode = QueryMode.Source(source),
    includeTerms = listOf(SearchTerm(tag)),
    excludeTerms = emptyList(),
    sort = SortMode.NEWEST,
    dateRange = null,
    minScore = null,
)

private fun unifiedQuery(tag: String) = query(SourceKey.PIXIV, tag).copy(mode = QueryMode.Unified)

private fun post(source: SourceKey, id: String) = Post(
    id = PostId(source, id),
    preview = ImageRef("https://example.test/$id.jpg", null, "image/jpeg"),
    full = null,
    pageUrl = null,
    width = 100,
    height = 100,
    canonicalTags = emptyList(),
    rawTags = emptyList(),
    authorName = null,
    createdAtEpochMs = null,
)
