package com.theoriacodex.app.search

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.query.QueryHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.theoriacodex.domain.query.SearchTermLimit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `a single-source search past the source's term cap is refused without a request`() = runTest {
        val danbooru = TestAdapter(SourceKey.DANBOORU, SearchTermLimit(maxTerms = 2))
        val coordinator = coordinator(danbooru)
        coordinator.initializeRoute()
        val query = Query(QueryMode.Source(SourceKey.DANBOORU), listOf("cat", "sky", "cloud"), emptyList(),
            SortMode.NEWEST, null, null)

        val result = coordinator.executeInitial(query, SearchSourceScope.Single(SourceKey.DANBOORU))
            as SearchExecutionResult.Failure

        assertEquals("Danbooru allows up to 2 tags per search.", result.message)
        assertTrue(danbooru.searchedQueries.isEmpty())
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
        ) as SearchExecutionResult.Failure

        assertEquals(SourceRunState.FAILED, result.statuses.single().state)
        assertFalse(requireNotNull(result.continuation).canLoadMore)
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
            sourceScope = SearchSourceScope.GlobalUnified,
            enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            availableSources = listOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            cursor = FeedCursor(
                query,
                weights = mapOf(SourceKey.PIXIV to 0.5, SourceKey.GELBOORU to 0.5),
                pageTokens = mapOf(SourceKey.PIXIV to "next", SourceKey.GELBOORU to "next"),
            ),
        )

        val result = coordinator.executePage(continuation) as SearchPageResult.Success

        assertEquals(listOf("page-1"), result.posts.map { it.id.sourcePostId })
        assertEquals(null, result.continuation.cursor.pageTokens[SourceKey.PIXIV])
        assertEquals(null, result.continuation.cursor.pageTokens[SourceKey.GELBOORU])
        assertFalse(result.continuation.canLoadMore)
    }

    @Test
    fun `temporary execution key binds canonical source selection`() = runTest {
        val query = unifiedQuery("temporary")
        val scope = SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV))

        assertEquals(
            "${QueryHash.from(query)}|temporary-sources:GELBOORU,PIXIV",
            searchExecutionKey(query, scope),
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
        val runtime = coordinator.initializeRoute().runtime
        val available = coordinator.availableSources.toSet()

        val gelbooruScope = SearchSourceScope.Single(SourceKey.GELBOORU)
        val source = coordinator.executeInitial(
            query(SourceKey.GELBOORU, "source"),
            gelbooruScope,
            SearchSources.of(runtime, gelbooruScope, available),
        ) as SearchExecutionResult.Success
        val unified = coordinator.executeInitial(
            unifiedQuery("unified"),
            SearchSourceScope.GlobalUnified,
            SearchSources.of(runtime, SearchSourceScope.GlobalUnified, available),
        ) as SearchExecutionResult.Success

        assertEquals(listOf("source"), gelbooru.searchedTags.first())
        assertEquals(1, source.statuses.size)
        assertEquals(listOf("unified"), pixiv.searchedTags.last())
        assertEquals(1, gelbooru.searchedTags.size)
        assertEquals(SourceRunState.EXCLUDED, unified.statuses.first { it.source == SourceKey.GELBOORU }.state)
    }

    @Test
    fun `temporary execution records Multi-Search while applied persistence remains disabled`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val gelbooru = TestAdapter(SourceKey.GELBOORU)
        val hitomi = TestAdapter(SourceKey.HITOMI)
        val queryRepository = InMemoryQueryRepository()
        val recents = InMemoryRecentsRepository()
        val statistics = InMemoryStatisticsRepository()
        val coordinator = SearchCoordinator(
            registry = TestRegistry(listOf(pixiv, gelbooru, hitomi)),
            queryRepository = queryRepository,
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            recentsRepository = recents,
            statisticsRepository = statistics,
        )
        coordinator.initializeRoute()
        val query = unifiedQuery("temporary")
        val scope = SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV))

        val result = coordinator.executeInitial(query, scope) as SearchExecutionResult.Success
        coordinator.persistAppliedSearch(result.query, result.sourceScope, result.executionKey)
        coordinator.recordAcceptedSearch(result)
        coordinator.executePage(result.continuation)

        assertEquals(2, pixiv.searchedTags.size)
        assertEquals(2, gelbooru.searchedTags.size)
        assertTrue(hitomi.searchedTags.isEmpty())
        val recentSearch = recents.observeSearches().first().single()
        assertEquals(RecentSearchKind.MULTI_SEARCH, recentSearch.kind)
        assertEquals(listOf(SourceKey.GELBOORU, SourceKey.PIXIV), recentSearch.sources)
        assertEquals(result.executionKey, recentSearch.queryHash)
        assertEquals(null, queryRepository.observeAppliedQuery("unified").first())
        val lifetime = statistics.observeStatistics().first()
        assertEquals(1L, lifetime.searchCount)
        assertEquals(1L, lifetime.searchesBySource.getValue(SourceKey.PIXIV))
        assertEquals(1L, lifetime.searchesBySource.getValue(SourceKey.GELBOORU))
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
        first.recordAcceptedSearch(result)
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
    fun `reapplying a query invalidates the previously cached scroll position`() = runTest {
        val restore = InMemoryUiRestoreRepository()
        val coordinator = coordinator(uiRestoreRepository = restore)
        val query = unifiedQuery("scroll-reset")
        val key = QueryHash.from(query)
        coordinator.persistSearchScrollState(12, 24, key)
        coordinator.persistAppliedSearch(query, SearchSourceScope.GlobalUnified, key)
        coordinator.persistSearchScrollState(12, 24, key)
        assertEquals(SearchScrollState(12, 24), restore.getSearchScrollState(key))
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
        )

        assertEquals("blue hair", pixiv.autocompletePrefixes.single())
        assertEquals("blue_hair", gelbooru.autocompletePrefixes.single())
        assertTrue(hitomi.autocompletePrefixes.isEmpty())
        assertEquals(
            listOf("blue_hair_mid", "blue_hair_high", "blue_hair_low"),
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
        val query = unifiedQuery("cat").withIncludeTermsAsRequired(listOf(SearchTerm("cat"), SearchTerm("najar", com.theoriacodex.domain.model.SearchFacet.ARTIST, "artist"))).copy(excludeTerms = listOf(SearchTerm("nsfw_(content)")))

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
        val coordinator = testSearchCoordinator(TestRegistry(listOf(adapter)), clock = { now })
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
        val coordinator = testSearchCoordinator(TestRegistry(listOf(adapter)))
        coordinator.initializeRoute()
        val query = query(SourceKey.HITOMI, "")

        val autocomplete = coordinator.fetchAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.HITOMI),
            FacetedSearchScope.All,
            "artist:naj",
        )
        val featured = coordinator.fetchAutocomplete(
            query,
            SearchSourceScope.Single(SourceKey.HITOMI),
            artist,
            "",
        )

        assertEquals(artist, autocomplete.selectedScope)
        assertEquals(listOf(suggestion), autocomplete.facetedAutocomplete)
        assertEquals(listOf(suggestion), featured.facetedAutocomplete)
        assertEquals(
            UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE,
            coordinator.fetchAutocomplete(
                unifiedQuery(""),
                SearchSourceScope.GlobalUnified,
                FacetedSearchScope.All,
                "artist:naj",
            ).validationMessage,
        )
        assertEquals(
            UNSUPPORTED_SEARCH_SCOPE_MESSAGE,
            coordinator.fetchAutocomplete(
                query,
                SearchSourceScope.Single(SourceKey.HITOMI),
                FacetedSearchScope.All,
                "female:naj",
            ).validationMessage,
        )
        assertAutocompleteCancellation(adapter, coordinator, query, artist)
    }

    @Test
    fun `tag count lookup caches batch results`() = runTest {
        val countDelegate = TestAdapter(SourceKey.GELBOORU)
        val countAdapter = CountAdapter(countDelegate, mapOf("blue_hair" to 321))
        val countStore = RecordingTagStore()
        val counts = testSearchCoordinator(TestRegistry(listOf(countAdapter)), tagSuggestionStore = countStore)
        counts.initializeRoute()

        assertEquals(321, counts.fetchTagVideoCounts(SourceKey.GELBOORU, listOf("blue hair"))["blue hair"])
        assertEquals(321, counts.fetchTagVideoCounts(SourceKey.GELBOORU, listOf("blue hair"))["blue hair"])
        assertEquals(1, countAdapter.calls)
    }

    @Test
    fun `temporary trending stays on selected sources and source order is stable`() = runTest {
        val pixiv = TestAdapter(SourceKey.PIXIV).apply { autocomplete = listOf(TagSuggestion("pixiv", "tag", 3)) }
        val gelbooru = TestAdapter(SourceKey.GELBOORU).apply { autocomplete = listOf(TagSuggestion("gelbooru", "tag", 2)) }
        val hitomi = TestAdapter(SourceKey.HITOMI).apply { autocomplete = listOf(TagSuggestion("hitomi", "tag", 1)) }
        val store = RecordingTagStore()
        val coordinator = testSearchCoordinator(
            TestRegistry(listOf(hitomi, gelbooru, pixiv)),
            tagSuggestionStore = store,
        )
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

    @Test
    fun `trending refreshed within its window is not fetched again after a restart`() = runTest {
        val nhentai = TestAdapter(SourceKey.NHENTAI).apply { autocomplete = listOf(TagSuggestion("sky", "tag", 3)) }
        val pixiv = TestAdapter(SourceKey.PIXIV)
        val store = RecordingTagStore()
        var now = 0L
        suspend fun launch() = testSearchCoordinator(TestRegistry(listOf(nhentai, pixiv)), tagSuggestionStore = store, clock = { now })
            .apply { initializeRoute() }
            .fetchTrending(query(SourceKey.NHENTAI, ""), SearchSourceScope.Temporary(listOf(SourceKey.NHENTAI, SourceKey.PIXIV)))

        launch()
        now = TRENDING_REFRESH_INTERVAL_MS - 1
        assertEquals(listOf("sky"), launch().map(TagSuggestion::text))
        assertEquals(1, nhentai.trendingCalls)
        now = TRENDING_REFRESH_INTERVAL_MS
        nhentai.autocomplete = emptyList()
        assertEquals(listOf("sky"), launch().map(TagSuggestion::text))
        assertEquals(2, nhentai.trendingCalls)
        now += TRENDING_REFRESH_INTERVAL_MS - 1
        launch()
        assertEquals(2, nhentai.trendingCalls)
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
        return testSearchCoordinator(
            registry = TestRegistry(adapters.toList()),
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
            recentsRepository = recentsRepository,
        )
    }

    private suspend fun assertAutocompleteCancellation(
        adapter: FacetedAdapter,
        coordinator: SearchCoordinator,
        query: Query,
        artist: FacetedSearchScope,
    ) {
        adapter.failure = CancellationException("scope changed")
        assertTrue(
            runCatching {
                coordinator.fetchAutocomplete(
                    query,
                    SearchSourceScope.Single(SourceKey.HITOMI),
                    artist,
                    "naja",
                )
            }.exceptionOrNull() is CancellationException,
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
