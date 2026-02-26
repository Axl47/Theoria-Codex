package com.theoriacodex.app.search

import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCoordinatorTest {
    @Test
    fun `draft apply reset transitions preserve explicit apply semantics`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()

        coordinator.addIncludeTag("landscape")
        assertTrue(coordinator.hasPendingChanges)
        assertTrue("landscape" in coordinator.draftQuery.includeTags)
        assertTrue(coordinator.appliedQuery.includeTags.isEmpty())

        coordinator.resetDraft()
        assertFalse(coordinator.hasPendingChanges)
        assertTrue(coordinator.draftQuery.includeTags.isEmpty())

        coordinator.addIncludeTag("landscape")
        coordinator.applyDraft()
        assertFalse(coordinator.hasPendingChanges)
        assertTrue("landscape" in coordinator.appliedQuery.includeTags)
    }

    @Test
    fun `query hash keyed scroll state and viewer launch context are restored`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))
        coordinator.addIncludeTag("portrait")
        coordinator.applyDraft()

        coordinator.persistSearchScrollState(index = 4, offsetPx = 120)
        val restored = coordinator.restoreSearchScrollState()
        val context = coordinator.buildViewerLaunchContext(startIndex = 2, scrollOffsetHint = 120)

        assertEquals(4, restored?.firstVisibleItemIndex)
        assertEquals(120, restored?.firstVisibleItemOffsetPx)
        assertEquals(coordinator.appliedQueryHash, context.queryHash)
        assertEquals(2, context.startIndex)
    }

    @Test
    fun `runtime source settings change requests refresh only after at least one execution`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        val firstChange = coordinator.onSettingsChanged(
            AppSettings(
                runtime = AppSettings().runtime.copy(
                    enabledSources = setOf(SourceKey.PIXIV),
                )
            )
        )
        assertFalse(firstChange)

        coordinator.applyDraft()
        val secondChange = coordinator.onSettingsChanged(
            AppSettings(
                runtime = AppSettings().runtime.copy(
                    enabledSources = setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
                )
            )
        )
        assertTrue(secondChange)
        assertNotNull(coordinator.statuses)
    }

    @Test
    fun `initialize restores executed-search state when previous apply was persisted`() = runTest {
        val queryRepository = InMemoryQueryRepository()
        val settingsRepository = InMemorySettingsRepository()
        val uiRestoreRepository = InMemoryUiRestoreRepository()

        val firstSession = SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
        )
        firstSession.initialize()
        assertFalse(firstSession.hasAnySearchRun)
        firstSession.applyDraft()
        assertTrue(firstSession.hasAnySearchRun)

        val restoredSession = SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
        )
        restoredSession.initialize()

        assertTrue(restoredSession.hasAnySearchRun)
    }

    @Test
    fun `mode options and source mode availability follow registry`() = runTest {
        val coordinator = SearchCoordinator(
            registry = LimitedStubRegistry(setOf(SourceKey.PIXIV)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()

        assertEquals(
            listOf(QueryMode.Unified, QueryMode.Source(SourceKey.PIXIV)),
            coordinator.modeOptions,
        )

        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))
        assertEquals(QueryMode.Unified, coordinator.draftQuery.mode)
    }

    @Test
    fun `clear draft resets to default query for current mode`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))
        coordinator.addIncludeTag("first")
        coordinator.applyDraft()
        coordinator.addIncludeTag("second")

        coordinator.clearDraft()

        assertTrue(coordinator.draftQuery.includeTags.isEmpty())
        assertTrue(coordinator.draftQuery.excludeTags.isEmpty())
        assertEquals(QueryMode.Source(SourceKey.PIXIV), coordinator.draftQuery.mode)
    }

    @Test
    fun `prepare explore tag search resets draft and clears prior runtime state`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.addIncludeTag("before")
        coordinator.applyDraft()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))
        coordinator.addExcludeTag("old-exclude")
        coordinator.setSort(SortMode.TOP)

        val prepared = coordinator.prepareExploreTagSearch(
            includeTags = listOf("fresh-tag", "fresh-tag"),
            excludeTags = listOf("blocked", "fresh-tag"),
        )

        assertTrue(prepared)
        assertEquals(QueryMode.Unified, coordinator.draftQuery.mode)
        assertEquals(listOf("fresh-tag"), coordinator.draftQuery.includeTags)
        assertEquals(listOf("blocked"), coordinator.draftQuery.excludeTags)
        assertEquals(SortMode.NEWEST, coordinator.draftQuery.sort)
        assertEquals(null, coordinator.draftQuery.dateRange)
        assertEquals(null, coordinator.draftQuery.minScore)
        assertTrue(coordinator.results.isEmpty())
        assertTrue(coordinator.statuses.isEmpty())
        assertEquals(null, coordinator.errorMessage)
    }

    @Test
    fun `prepare explore tag search rejects empty selections`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()

        val prepared = coordinator.prepareExploreTagSearch(
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertFalse(prepared)
    }

    @Test
    fun `quick query resets prior include and exclude tags`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.addIncludeTag("from-trending")
        coordinator.addExcludeTag("old-exclude")
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))

        coordinator.applyQuickQuery(QuickQueryKind.TOP_7D)

        assertEquals(QueryMode.Unified, coordinator.draftQuery.mode)
        assertTrue(coordinator.draftQuery.includeTags.isEmpty())
        assertTrue(coordinator.draftQuery.excludeTags.isEmpty())
        assertEquals(SortMode.TOP, coordinator.draftQuery.sort)
        assertTrue(coordinator.draftQuery.dateRange != null)
    }

    @Test
    fun `gelbooru source mode only commits typed tags from suggestions`() = runTest {
        val registry = CompatibilityRegistry(
            adapters = mapOf(
                SourceKey.GELBOORU to RecordingAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    autocompleteByPrefix = mapOf(
                        "land" to listOf("landscape"),
                        "safe" to listOf("safe"),
                    ),
                ),
            ),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))

        coordinator.refreshAutocompleteSuggestions("portrait")
        assertFalse(coordinator.commitTagInput("portrait"))
        assertTrue(coordinator.tagInputValidationMessage?.contains("Gelbooru", ignoreCase = true) == true)

        coordinator.refreshAutocompleteSuggestions("land")
        assertTrue(coordinator.commitTagInput("landscape"))
        assertTrue("landscape" in coordinator.draftQuery.includeTags)

        coordinator.refreshAutocompleteSuggestions("safe")
        assertTrue(coordinator.commitTagInput("-safe"))
        assertTrue("safe" in coordinator.draftQuery.excludeTags)
        assertNull(coordinator.tagInputValidationMessage)
    }

    @Test
    fun `unified search maps gelbooru compatibility tags and falls back to raw tag`() = runTest {
        val pixivAdapter = RecordingAdapter(sourceKey = SourceKey.PIXIV)
        val gelbooruAdapter = RecordingAdapter(
            sourceKey = SourceKey.GELBOORU,
            autocompleteByPrefix = mapOf(
                "cat" to listOf("cat_(animal)"),
                "dog" to emptyList(),
            ),
        )
        val registry = CompatibilityRegistry(
            adapters = mapOf(
                SourceKey.PIXIV to pixivAdapter,
                SourceKey.GELBOORU to gelbooruAdapter,
            ),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.addIncludeTag("cat")
        coordinator.addIncludeTag("dog")

        coordinator.applyDraft()

        assertEquals(listOf("cat", "dog"), pixivAdapter.lastSearchQuery?.includeTags)
        assertEquals(listOf("cat_(animal)", "dog"), gelbooruAdapter.lastSearchQuery?.includeTags)
    }

    @Test
    fun `autocomplete suggestions are sorted by post count descending`() = runTest {
        val registry = CompatibilityRegistry(
            adapters = mapOf(
                SourceKey.GELBOORU to RecordingAdapter(
                    sourceKey = SourceKey.GELBOORU,
                    autocompleteTagsByPrefix = mapOf(
                        "land" to listOf(
                            TagSuggestion(text = "landscape_low", type = "tag", count = 10),
                            TagSuggestion(text = "landscape_high", type = "tag", count = 500),
                            TagSuggestion(text = "landscape_mid", type = "tag", count = 120),
                        ),
                    ),
                ),
            ),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))

        coordinator.refreshAutocompleteSuggestions("land")

        assertEquals(
            listOf("landscape_high", "landscape_mid", "landscape_low"),
            coordinator.autocompleteSuggestions.map { it.text },
        )
    }

    @Test
    fun `pixiv unknown failure resets search and prompts retry message`() = runTest {
        val registry = object : SourceAdapterRegistry {
            private val pixivAdapter = object : SourceAdapter {
                override val sourceKey: SourceKey = SourceKey.PIXIV
                override val capabilities: SourceCapabilities = SourceCapabilities(
                    supportsSortNewest = true,
                    supportsSortPopular = true,
                    supportsSortTop = true,
                    supportsSortRandom = true,
                    supportsExcludeTagsServerSide = false,
                    supportsDateRangeServerSide = false,
                    supportsMinScoreServerSide = false,
                    requiresCredentials = true,
                )

                override suspend fun search(query: Query, pageToken: String?): Page<Post> {
                    throw SourceAdapterException(
                        reason = SourceFailureReason.UNKNOWN,
                        message = "PIXIV_UNKNOWN",
                    )
                }

                override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

                override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

                override suspend fun quickQuery(kind: QuickQueryKind): Query = query

                override suspend fun resolvePost(id: PostId): Post? = null

                private val query = Query(
                    mode = QueryMode.Source(SourceKey.PIXIV),
                    includeTags = emptyList(),
                    excludeTags = emptyList(),
                    sort = com.theoriacodex.domain.model.SortMode.NEWEST,
                    dateRange = null,
                    minScore = null,
                )
            }

            override fun availableSources(): Set<SourceKey> = setOf(SourceKey.PIXIV)

            override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = pixivAdapter.takeIf {
                sourceKey == SourceKey.PIXIV
            }

            override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
                return UnifiedSearchOrchestrator(mapOf(SourceKey.PIXIV to pixivAdapter))
            }
        }
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))

        coordinator.applyDraft()

        assertTrue(coordinator.results.isEmpty())
        assertTrue(coordinator.errorMessage?.contains("Search was reset", ignoreCase = true) == true)
    }

    private fun coordinator(): SearchCoordinator {
        return SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
    }
}

private class LimitedStubRegistry(
    private val available: Set<SourceKey>,
) : SourceAdapterRegistry {
    private val delegate = StubAdapterRegistry()
    private val adaptersBySource: Map<SourceKey, SourceAdapter> = available.associateWith { source ->
        requireNotNull(delegate.adapterFor(source))
    }

    override fun availableSources(): Set<SourceKey> = available

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? {
        return adaptersBySource[sourceKey]
    }

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator {
        return UnifiedSearchOrchestrator(adaptersBySource)
    }
}

private class CompatibilityRegistry(
    private val adapters: Map<SourceKey, SourceAdapter>,
) : SourceAdapterRegistry {
    private val orchestrator = UnifiedSearchOrchestrator(adapters)

    override fun availableSources(): Set<SourceKey> = adapters.keys

    override fun adapterFor(sourceKey: SourceKey): SourceAdapter? = adapters[sourceKey]

    override fun unifiedOrchestrator(): UnifiedSearchOrchestrator = orchestrator
}

private class RecordingAdapter(
    override val sourceKey: SourceKey,
    private val autocompleteByPrefix: Map<String, List<String>> = emptyMap(),
    private val autocompleteTagsByPrefix: Map<String, List<TagSuggestion>> = emptyMap(),
) : SourceAdapter {
    var lastSearchQuery: Query? = null

    override val capabilities: SourceCapabilities = SourceCapabilities(
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
        lastSearchQuery = query
        return Page(items = emptyList(), nextPageToken = null)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        val normalized = prefix.trim().lowercase()
        val richMatches = autocompleteTagsByPrefix[normalized]
            ?: autocompleteTagsByPrefix.entries.firstOrNull { (key, _) ->
                normalized.startsWith(key.lowercase()) || key.lowercase().startsWith(normalized)
            }?.value
        if (richMatches != null) {
            return richMatches.take(limit)
        }

        val matches = autocompleteByPrefix[normalized]
            ?: autocompleteByPrefix.entries.firstOrNull { (key, _) ->
                normalized.startsWith(key.lowercase()) || key.lowercase().startsWith(normalized)
            }?.value
            ?: emptyList()
        return matches
            .take(limit)
            .map { tag -> TagSuggestion(text = tag, type = "tag", count = null) }
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

    override suspend fun resolvePost(id: PostId): Post? = null
}
