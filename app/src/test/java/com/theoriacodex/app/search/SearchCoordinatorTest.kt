package com.theoriacodex.app.search

import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
    fun `apply draft records search history and retry does not duplicate it`() = runTest {
        var now = 1_000L
        val recentsRepository = InMemoryRecentsRepository(clock = { now })
        val coordinator = SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            recentsRepository = recentsRepository,
        )
        coordinator.initialize()
        coordinator.addIncludeTag("landscape")

        coordinator.applyDraft()
        val firstHistory = recentsRepository.observeSearches().first()
        now += 1
        coordinator.retry()
        val afterRetryHistory = recentsRepository.observeSearches().first()
        now += 1
        coordinator.applyDraft()
        val afterReapplyHistory = recentsRepository.observeSearches().first()

        assertEquals(1, firstHistory.size)
        assertEquals(listOf("landscape"), firstHistory.first().query.includeTags)
        assertEquals(firstHistory, afterRetryHistory)
        assertEquals(1, afterReapplyHistory.size)
        assertEquals(now, afterReapplyHistory.first().searchedAtEpochMs)
    }

    @Test
    fun `historical query apply restores query records search and executes`() = runTest {
        val recentsRepository = InMemoryRecentsRepository(clock = { 2_000L })
        val coordinator = SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            recentsRepository = recentsRepository,
        )
        coordinator.initialize()
        val historicalQuery = Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("portrait"),
            excludeTags = listOf("sketch"),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = 25,
        )

        val applied = coordinator.applyHistoricalQuery(historicalQuery)

        assertTrue(applied)
        assertEquals(historicalQuery, coordinator.appliedQuery)
        assertEquals(historicalQuery, coordinator.draftQuery)
        assertFalse(coordinator.hasPendingChanges)
        assertTrue(coordinator.hasAnySearchRun)
        assertEquals(historicalQuery, recentsRepository.observeSearches().first().single().query)
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
    fun `initialize restores last applied source mode after restart`() = runTest {
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
        firstSession.setMode(QueryMode.Source(SourceKey.PIXIV))
        firstSession.addIncludeTag("landscape")
        firstSession.applyDraft()

        val restoredSession = SearchCoordinator(
            registry = StubAdapterRegistry(),
            queryRepository = queryRepository,
            settingsRepository = settingsRepository,
            uiRestoreRepository = uiRestoreRepository,
        )
        restoredSession.initialize()

        assertEquals(QueryMode.Source(SourceKey.PIXIV), restoredSession.appliedQuery.mode)
        assertEquals(QueryMode.Source(SourceKey.PIXIV), restoredSession.draftQuery.mode)
        assertTrue("landscape" in restoredSession.appliedQuery.includeTags)
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
    fun `source mode search ignores unified enabled-source toggles`() = runTest {
        val gelbooruAdapter = RecordingAdapter(sourceKey = SourceKey.GELBOORU)
        val settingsRepository = InMemorySettingsRepository()
        settingsRepository.setEnabledSources(setOf(SourceKey.PIXIV))
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                adapters = mapOf(SourceKey.GELBOORU to gelbooruAdapter),
            ),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = settingsRepository,
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))
        coordinator.addIncludeTag("landscape")

        coordinator.applyDraft()

        assertEquals(listOf("landscape"), gelbooruAdapter.lastSearchQuery?.includeTags)
        assertEquals(1, coordinator.statuses.size)
        assertEquals(SourceKey.GELBOORU, coordinator.statuses.single().source)
        assertEquals(SourceRunState.SUCCESS, coordinator.statuses.single().state)
    }

    @Test
    fun `unified search still excludes disabled sources from settings`() = runTest {
        val pixivAdapter = RecordingAdapter(sourceKey = SourceKey.PIXIV)
        val gelbooruAdapter = RecordingAdapter(sourceKey = SourceKey.GELBOORU)
        val settingsRepository = InMemorySettingsRepository()
        settingsRepository.setEnabledSources(setOf(SourceKey.PIXIV))
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                adapters = mapOf(
                    SourceKey.PIXIV to pixivAdapter,
                    SourceKey.GELBOORU to gelbooruAdapter,
                ),
            ),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = settingsRepository,
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.addIncludeTag("landscape")

        coordinator.applyDraft()

        assertEquals(listOf("landscape"), pixivAdapter.lastSearchQuery?.includeTags)
        assertNull(gelbooruAdapter.lastSearchQuery)
        assertTrue(
            coordinator.statuses.any { status ->
                status.source == SourceKey.GELBOORU && status.state == SourceRunState.EXCLUDED
            }
        )
    }

    @Test
    fun `available source ordering inserts iwara after nhentai`() = runTest {
        val coordinator = SearchCoordinator(
            registry = LimitedStubRegistry(
                setOf(
                    SourceKey.RULE34VIDEO,
                    SourceKey.IWARA,
                    SourceKey.NHENTAI,
                    SourceKey.PIXIV,
                ),
            ),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()

        assertEquals(
            listOf(SourceKey.PIXIV, SourceKey.NHENTAI, SourceKey.IWARA, SourceKey.RULE34VIDEO),
            coordinator.availableSources,
        )
    }

    @Test
    fun `display results overlays remembered resolved posts for current query`() = runTest {
        val raw = samplePost()
        val resolved = raw.copy(
            full = ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4"),
            media = listOf(ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4")),
        )
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.IWARA,
            searchResults = listOf(raw),
        ) { resolved }
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.IWARA to adapter)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.IWARA))
        coordinator.applyDraft()

        coordinator.rememberResolvedPost(resolved)

        assertEquals("https://cdn.iwara.tv/video.mp4", coordinator.displayResults().single().full?.url)
    }

    @Test
    fun `resolved post overlay is query scoped`() = runTest {
        val raw = samplePost()
        val resolved = raw.copy(
            full = ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4"),
            media = listOf(ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4")),
        )
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.IWARA,
            searchResults = listOf(raw),
        ) { resolved }
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.IWARA to adapter)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.IWARA))
        coordinator.addIncludeTag("alpha")
        coordinator.applyDraft()
        coordinator.rememberResolvedPost(resolved)

        coordinator.clearDraft()
        coordinator.addIncludeTag("beta")
        coordinator.applyDraft()

        assertNull(coordinator.displayResults().single().full)
    }

    @Test
    fun `resolve post for search stores successful resolved post and reuses it`() = runTest {
        val raw = samplePost()
        val resolved = raw.copy(
            full = ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4"),
            media = listOf(ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4")),
        )
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.IWARA,
            searchResults = listOf(raw),
        ) { resolved }
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.IWARA to adapter)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.IWARA))
        coordinator.applyDraft()

        val first = coordinator.resolvePostForSearch(raw.id)
        val second = coordinator.resolvePostForSearch(raw.id)

        assertEquals("https://cdn.iwara.tv/video.mp4", first?.full?.url)
        assertEquals("https://cdn.iwara.tv/video.mp4", second?.full?.url)
        assertEquals("https://cdn.iwara.tv/video.mp4", coordinator.displayResults().single().full?.url)
        assertEquals(1, adapter.resolveCallCount)
    }

    @Test
    fun `resolve post for search defers immediate retry after rate limit`() = runTest {
        var now = 1_000L
        val raw = samplePost()
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.IWARA,
            searchResults = listOf(raw),
        ) {
            throw SourceAdapterException(
                reason = SourceFailureReason.RATE_LIMITED,
                message = "429",
            )
        }
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.IWARA to adapter)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            clock = { now },
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.IWARA))
        coordinator.applyDraft()

        assertNull(coordinator.resolvePostForSearch(raw.id))
        assertTrue(coordinator.shouldDeferResolve(raw.id))
        assertEquals(1, adapter.resolveCallCount)

        assertNull(coordinator.resolvePostForSearch(raw.id))
        assertEquals(1, adapter.resolveCallCount)

        now += 31_000L
        assertFalse(coordinator.shouldDeferResolve(raw.id))
        assertNull(coordinator.resolvePostForSearch(raw.id))
        assertEquals(2, adapter.resolveCallCount)
        assertTrue(coordinator.shouldDeferResolve(raw.id))
    }

    @Test
    fun `display results stay resolved after retry refresh for same query`() = runTest {
        val raw = samplePost()
        val resolved = raw.copy(
            full = ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4"),
            media = listOf(ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4")),
        )
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.IWARA,
            searchResults = listOf(raw),
        ) { resolved }
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.IWARA to adapter)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.IWARA))
        coordinator.applyDraft()
        coordinator.rememberResolvedPost(resolved)

        coordinator.retry()

        assertEquals("https://cdn.iwara.tv/video.mp4", coordinator.displayResults().single().full?.url)
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
    fun `prepare tag search resets draft and clears prior runtime state`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.addIncludeTag("before")
        coordinator.applyDraft()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))
        coordinator.addExcludeTag("old-exclude")
        coordinator.setSort(SortMode.TOP)

        val prepared = coordinator.prepareTagSearch(
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
    fun `prepare tag search can target an available source mode`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()

        val prepared = coordinator.prepareTagSearch(
            includeTags = listOf("fresh-tag"),
            mode = QueryMode.Source(SourceKey.PIXIV),
        )

        assertTrue(prepared)
        assertEquals(QueryMode.Source(SourceKey.PIXIV), coordinator.draftQuery.mode)
        assertEquals(listOf("fresh-tag"), coordinator.draftQuery.includeTags)
        assertEquals(SortMode.NEWEST, coordinator.draftQuery.sort)
    }

    @Test
    fun `prepare tag search rejects unavailable source mode`() = runTest {
        val coordinator = SearchCoordinator(
            registry = LimitedStubRegistry(setOf(SourceKey.PIXIV)),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()

        val prepared = coordinator.prepareTagSearch(
            includeTags = listOf("fresh-tag"),
            mode = QueryMode.Source(SourceKey.GELBOORU),
        )

        assertFalse(prepared)
        assertEquals(QueryMode.Unified, coordinator.draftQuery.mode)
        assertTrue(coordinator.draftQuery.includeTags.isEmpty())
    }

    @Test
    fun `prepare tag search rejects empty selections`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()

        val prepared = coordinator.prepareTagSearch(
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertFalse(prepared)
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
            tagSuggestionStore = InMemoryTagSuggestionStore(),
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
    fun `gelbooru autocomplete treats spaces as underscores`() = runTest {
        val gelbooruAdapter = RecordingAdapter(
            sourceKey = SourceKey.GELBOORU,
            autocompleteByPrefix = mapOf(
                "blue_hair" to listOf("blue_hair"),
            ),
        )
        val registry = CompatibilityRegistry(
            adapters = mapOf(SourceKey.GELBOORU to gelbooruAdapter),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            tagSuggestionStore = InMemoryTagSuggestionStore(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))

        coordinator.refreshAutocompleteSuggestions("blue hair")

        assertEquals("blue_hair", gelbooruAdapter.lastAutocompletePrefix)
        assertEquals(listOf("blue_hair"), coordinator.autocompleteSuggestions.map { it.text })
        assertTrue(coordinator.canCommitTagInput("blue hair"))
        assertTrue(coordinator.commitTagInput("blue hair"))
        assertTrue("blue_hair" in coordinator.draftQuery.includeTags)
    }

    @Test
    fun `iwara autocomplete treats spaces as underscores`() = runTest {
        val iwaraAdapter = RecordingAdapter(
            sourceKey = SourceKey.IWARA,
            autocompleteByPrefix = mapOf(
                "blue_hair" to listOf("blue_hair"),
            ),
        )
        val registry = CompatibilityRegistry(
            adapters = mapOf(SourceKey.IWARA to iwaraAdapter),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            tagSuggestionStore = InMemoryTagSuggestionStore(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.IWARA))

        coordinator.refreshAutocompleteSuggestions("blue hair")

        assertEquals("blue_hair", iwaraAdapter.lastAutocompletePrefix)
        assertEquals(listOf("blue_hair"), coordinator.autocompleteSuggestions.map { it.text })
        assertTrue(coordinator.canCommitTagInput("blue hair"))
        assertTrue(coordinator.commitTagInput("blue hair"))
        assertTrue("blue_hair" in coordinator.draftQuery.includeTags)
    }

    @Test
    fun `pixiv source mode smart add resolves to suggested canonical tag`() = runTest {
        val pixivAdapter = RecordingAdapter(
            sourceKey = SourceKey.PIXIV,
            autocompleteByPrefix = mapOf(
                "blue hair" to listOf("blue_hair"),
            ),
        )
        val registry = CompatibilityRegistry(
            adapters = mapOf(SourceKey.PIXIV to pixivAdapter),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            tagSuggestionStore = InMemoryTagSuggestionStore(),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))

        coordinator.refreshAutocompleteSuggestions("blue hair")

        assertTrue(coordinator.canCommitTagInput("blue hair"))
        assertTrue(coordinator.commitTagInput("blue hair"))
        assertTrue("blue_hair" in coordinator.draftQuery.includeTags)
        assertFalse("blue hair" in coordinator.draftQuery.includeTags)
    }

    @Test
    fun `nhentai language filter toggles language tags in include list`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))
        coordinator.addIncludeTag("artist:example")

        coordinator.setNhentaiLanguageFilter(NhentaiLanguageFilter.CHINESE)
        assertEquals(NhentaiLanguageFilter.CHINESE, coordinator.selectedNhentaiLanguageFilter())
        assertTrue("chinese" in coordinator.draftQuery.includeTags)

        coordinator.setNhentaiLanguageFilter(NhentaiLanguageFilter.JAPANESE)
        assertEquals(NhentaiLanguageFilter.JAPANESE, coordinator.selectedNhentaiLanguageFilter())
        assertFalse("chinese" in coordinator.draftQuery.includeTags)
        assertTrue("japanese" in coordinator.draftQuery.includeTags)

        coordinator.setNhentaiLanguageFilter(NhentaiLanguageFilter.ANY)
        assertEquals(NhentaiLanguageFilter.ANY, coordinator.selectedNhentaiLanguageFilter())
        assertFalse("japanese" in coordinator.draftQuery.includeTags)
        assertTrue("artist:example" in coordinator.draftQuery.includeTags)
    }

    @Test
    fun `nhentai full color filter toggles full color tag in include list`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))
        coordinator.addIncludeTag("artist:example")

        coordinator.setNhentaiFullColorFilter(true)
        assertTrue(coordinator.selectedNhentaiFullColorFilter())
        assertTrue("full color" in coordinator.draftQuery.includeTags)

        coordinator.setNhentaiFullColorFilter(false)
        assertFalse(coordinator.selectedNhentaiFullColorFilter())
        assertFalse("full color" in coordinator.draftQuery.includeTags)
        assertTrue("artist:example" in coordinator.draftQuery.includeTags)
    }

    @Test
    fun `direct nhentai gallery id candidate supports source and unified modes`() = runTest {
        val coordinator = coordinator()
        coordinator.initialize()

        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))
        coordinator.addIncludeTag("634609")
        assertEquals("634609", coordinator.directNhentaiGalleryIdCandidate())
        coordinator.setNhentaiLanguageFilter(NhentaiLanguageFilter.ENGLISH)
        assertEquals("634609", coordinator.directNhentaiGalleryIdCandidate())
        coordinator.setNhentaiFullColorFilter(true)
        assertEquals("634609", coordinator.directNhentaiGalleryIdCandidate())

        coordinator.setMode(QueryMode.Unified)
        coordinator.addIncludeTag("634609")
        assertEquals("634609", coordinator.directNhentaiGalleryIdCandidate())

        coordinator.addExcludeTag("english")
        assertNull(coordinator.directNhentaiGalleryIdCandidate())
    }

    @Test
    fun `gelbooru batch tag counts are cached for subsequent lookups`() = runTest {
        val gelbooruAdapter = RecordingAdapter(
            sourceKey = SourceKey.GELBOORU,
            tagCountsByName = mapOf("blue_hair" to 321),
        )
        val registry = CompatibilityRegistry(
            adapters = mapOf(SourceKey.GELBOORU to gelbooruAdapter),
        )
        val coordinator = SearchCoordinator(
            registry = registry,
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
            tagSuggestionStore = InMemoryTagSuggestionStore(),
        )
        coordinator.initialize()

        val first = coordinator.fetchTagVideoCounts(SourceKey.GELBOORU, listOf("blue hair"))

        assertEquals(321, first["blue hair"])
        assertEquals(1, gelbooruAdapter.batchTagLookupCount)
        assertEquals(321, coordinator.tagVideoCount(SourceKey.GELBOORU, "blue hair"))

        coordinator.fetchTagVideoCounts(SourceKey.GELBOORU, listOf("blue hair"))
        assertEquals(1, gelbooruAdapter.batchTagLookupCount)
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
    fun `unified search normalizes pixiv tags by removing underscores and trailing disambiguation`() = runTest {
        val pixivAdapter = RecordingAdapter(sourceKey = SourceKey.PIXIV)
        val gelbooruAdapter = RecordingAdapter(sourceKey = SourceKey.GELBOORU)
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
        coordinator.addIncludeTag("this_is_a_tag_(Game)")
        coordinator.addExcludeTag("nsfw_(content)")

        coordinator.applyDraft()

        assertEquals(listOf("this is a tag"), pixivAdapter.lastSearchQuery?.includeTags)
        assertEquals(listOf("nsfw"), pixivAdapter.lastSearchQuery?.excludeTags)
        assertEquals(listOf("this_is_a_tag_(Game)"), gelbooruAdapter.lastSearchQuery?.includeTags)
        assertEquals(listOf("nsfw_(content)"), gelbooruAdapter.lastSearchQuery?.excludeTags)
    }

    @Test
    fun `unified compatibility mapping never promotes source facets into portable tags`() = runTest {
        val pixivAdapter = RecordingAdapter(sourceKey = SourceKey.PIXIV)
        val gelbooruAdapter = RecordingAdapter(sourceKey = SourceKey.GELBOORU)
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                adapters = mapOf(
                    SourceKey.PIXIV to pixivAdapter,
                    SourceKey.GELBOORU to gelbooruAdapter,
                ),
            ),
            queryRepository = InMemoryQueryRepository(),
            settingsRepository = InMemorySettingsRepository(),
            uiRestoreRepository = InMemoryUiRestoreRepository(),
        )
        coordinator.initialize()
        val query = Query(
            mode = QueryMode.Unified,
            includeTerms = listOf(
                SearchTerm(value = "portable_tag"),
                SearchTerm(value = "najar", facet = SearchFacet.ARTIST, sourceNamespace = "artist"),
                SearchTerm(value = "owned", sourceNamespace = "hitomi"),
            ),
            excludeTerms = listOf(
                SearchTerm(value = "portable_exclusion"),
                SearchTerm(value = "series name", facet = SearchFacet.SERIES, sourceNamespace = "series"),
            ),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = null,
        )

        coordinator.applyHistoricalQuery(query)

        assertEquals(listOf("portable tag"), pixivAdapter.lastSearchQuery?.includeTags)
        assertEquals(listOf("portable exclusion"), pixivAdapter.lastSearchQuery?.excludeTags)
        assertEquals(listOf("portable_tag"), gelbooruAdapter.lastSearchQuery?.includeTags)
        assertEquals(listOf("portable_exclusion"), gelbooruAdapter.lastSearchQuery?.excludeTags)
    }

    @Test
    fun `faceted source exposes scopes and resets ephemeral selection on mode change`() = runTest {
        val all = FacetedSearchScope.All
        val tags = FacetedSearchScope(SearchFacet.TAG, "tag")
        val artists = FacetedSearchScope(SearchFacet.ARTIST, "artist")
        val series = FacetedSearchScope(SearchFacet.SERIES, "parody")
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                adapters = mapOf(
                    SourceKey.NHENTAI to FacetedRecordingAdapter(
                        sourceKey = SourceKey.NHENTAI,
                        supportedSearchScopes = linkedSetOf(series, artists, tags, all),
                    ),
                    SourceKey.GELBOORU to RecordingAdapter(SourceKey.GELBOORU),
                ),
            ),
        )
        coordinator.initialize()

        assertTrue(coordinator.supportedSearchScopes.isEmpty())
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))
        assertEquals(listOf(all, tags, artists, series), coordinator.supportedSearchScopes)
        assertTrue(coordinator.selectSearchScope(artists))
        assertEquals(artists, coordinator.selectedSearchScope)
        assertFalse(coordinator.selectSearchScope(FacetedSearchScope(SearchFacet.GROUP, "group")))

        coordinator.setMode(QueryMode.Source(SourceKey.GELBOORU))

        assertTrue(coordinator.supportedSearchScopes.isEmpty())
        assertEquals(FacetedSearchScope.All, coordinator.selectedSearchScope)
    }

    @Test
    fun `faceted autocomplete keeps suggestion identity through selection and removal`() = runTest {
        val artists = FacetedSearchScope(SearchFacet.ARTIST, "artist")
        val artistSuggestion = FacetedTagSuggestion(
            text = "najar",
            facet = SearchFacet.ARTIST,
            sourceNamespace = "artist",
            count = 42,
        )
        val adapter = FacetedRecordingAdapter(
            sourceKey = SourceKey.NHENTAI,
            supportedSearchScopes = linkedSetOf(FacetedSearchScope.All, artists),
            suggestionsByScope = mapOf(artists to listOf(artistSuggestion)),
        )
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.NHENTAI to adapter)),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))

        coordinator.refreshAutocompleteSuggestions("artist:naj")

        assertEquals(artists, coordinator.selectedSearchScope)
        assertEquals("naj", adapter.lastFacetedPrefix)
        assertEquals(artists, adapter.lastFacetedScope)
        assertEquals(listOf(artistSuggestion), coordinator.facetedAutocompleteSuggestions)
        assertEquals(listOf("najar"), coordinator.autocompleteSuggestions.map(TagSuggestion::text))

        assertTrue(coordinator.addIncludeSuggestion(artistSuggestion))
        val tagSuggestion = artistSuggestion.copy(
            facet = SearchFacet.TAG,
            sourceNamespace = "tag",
        )
        assertTrue(coordinator.addIncludeSuggestion(tagSuggestion))
        assertEquals(2, coordinator.draftQuery.includeTerms.size)

        coordinator.removeIncludeTerm(artistSuggestion.toSearchTerm())

        assertEquals(listOf(tagSuggestion.toSearchTerm()), coordinator.draftQuery.includeTerms)
        assertTrue(coordinator.addExcludeSuggestion(artistSuggestion))
        coordinator.removeExcludeTerm(artistSuggestion.toSearchTerm())
        assertTrue(coordinator.draftQuery.excludeTerms.isEmpty())
    }

    @Test
    fun `unified autocomplete projects only general tags from faceted sources`() = runTest {
        val all = FacetedSearchScope.All
        val adapter = FacetedRecordingAdapter(
            sourceKey = SourceKey.NHENTAI,
            supportedSearchScopes = linkedSetOf(
                all,
                FacetedSearchScope(SearchFacet.TAG, "tag"),
                FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            ),
            suggestionsByScope = mapOf(
                all to listOf(
                    FacetedTagSuggestion("najar", SearchFacet.TAG, "tag", count = 5),
                    FacetedTagSuggestion("najar", SearchFacet.ARTIST, "artist", count = 42),
                ),
            ),
        )
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.NHENTAI to adapter)),
        )
        coordinator.initialize()

        coordinator.refreshAutocompleteSuggestions("najar")

        assertEquals(listOf("najar"), coordinator.autocompleteSuggestions.map(TagSuggestion::text))
        assertEquals(listOf("tag"), coordinator.autocompleteSuggestions.map(TagSuggestion::type))
        assertTrue(coordinator.facetedAutocompleteSuggestions.isEmpty())
        coordinator.addIncludeTag(coordinator.autocompleteSuggestions.single().text)
        assertEquals(listOf(SearchTerm("najar")), coordinator.draftQuery.includeTerms)
    }

    @Test
    fun `unsupported scoped autocomplete clears suggestions before commit`() = runTest {
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                mapOf(
                    SourceKey.NHENTAI to FacetedRecordingAdapter(
                        sourceKey = SourceKey.NHENTAI,
                        supportedSearchScopes = linkedSetOf(
                            FacetedSearchScope.All,
                            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
                        ),
                    ),
                ),
            ),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))

        coordinator.refreshAutocompleteSuggestions("series:idolmaster")

        assertTrue(coordinator.autocompleteSuggestions.isEmpty())
        assertTrue(coordinator.facetedAutocompleteSuggestions.isEmpty())
        assertTrue(coordinator.tagInputValidationMessage?.contains("not supported") == true)
        assertFalse(coordinator.commitTagInput("series:idolmaster"))
    }

    @Test
    fun `faceted autocomplete rethrows cancellation`() = runTest {
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                mapOf(
                    SourceKey.NHENTAI to FacetedRecordingAdapter(
                        sourceKey = SourceKey.NHENTAI,
                        supportedSearchScopes = linkedSetOf(FacetedSearchScope.All),
                        autocompleteFailure = CancellationException("scope changed"),
                    ),
                ),
            ),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))

        val error = runCatching {
            coordinator.refreshAutocompleteSuggestions("najar")
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    @Test
    fun `seen tag ingestion preserves same-text source namespaces`() = runTest {
        val store = RecordingFacetedSuggestionStore()
        val post = samplePost(source = SourceKey.NHENTAI).copy(
            canonicalTags = listOf("shared"),
            taxonomy = listOf(
                com.theoriacodex.domain.model.PostTaxonomyTerm("shared", SearchFacet.TAG, "female"),
                com.theoriacodex.domain.model.PostTaxonomyTerm("shared", SearchFacet.TAG, "male"),
            ),
        )
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.NHENTAI,
            searchResults = listOf(post),
            resolveBlock = { null },
        )
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.NHENTAI to adapter)),
            tagSuggestionStore = store,
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))

        coordinator.applyDraft()

        assertEquals(
            listOf("female", "male"),
            store.facetedSuggestions.map(FacetedTagSuggestion::sourceNamespace),
        )
    }

    @Test
    fun `seen tag ingestion keeps pixiv native raw tags instead of translated aliases`() = runTest {
        val store = RecordingFacetedSuggestionStore()
        val post = samplePost(source = SourceKey.PIXIV).copy(
            canonicalTags = listOf("猫", "cat"),
            rawTags = listOf("猫"),
            taxonomy = listOf(
                com.theoriacodex.domain.model.PostTaxonomyTerm("猫"),
                com.theoriacodex.domain.model.PostTaxonomyTerm("cat"),
            ),
        )
        val adapter = SearchResolveAdapter(
            sourceKey = SourceKey.PIXIV,
            searchResults = listOf(post),
            resolveBlock = { null },
        )
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(mapOf(SourceKey.PIXIV to adapter)),
            tagSuggestionStore = store,
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.PIXIV))

        coordinator.applyDraft()

        assertEquals(listOf("猫"), store.facetedSuggestions.map(FacetedTagSuggestion::text))
    }

    @Test
    fun `raw scoped prefixes auto-select source scope and preserve positive and negative meaning`() = runTest {
        val scopes = linkedSetOf(
            FacetedSearchScope.All,
            FacetedSearchScope(SearchFacet.TAG, "tag"),
            FacetedSearchScope(SearchFacet.TAG, "female"),
            FacetedSearchScope(SearchFacet.TAG, "male"),
            FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            FacetedSearchScope(SearchFacet.CHARACTER, "character"),
            FacetedSearchScope(SearchFacet.SERIES, "parody"),
            FacetedSearchScope(SearchFacet.GROUP, "group"),
            FacetedSearchScope(SearchFacet.TYPE, "category"),
            FacetedSearchScope(SearchFacet.LANGUAGE, "language"),
        )
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                mapOf(
                    SourceKey.NHENTAI to FacetedRecordingAdapter(
                        sourceKey = SourceKey.NHENTAI,
                        supportedSearchScopes = scopes,
                    ),
                ),
            ),
        )
        coordinator.initialize()
        coordinator.setMode(QueryMode.Source(SourceKey.NHENTAI))

        assertTrue(coordinator.commitTagInput("artist:najar"))
        assertTrue(coordinator.commitTagInput("series:the idolmaster"))
        assertTrue(coordinator.commitTagInput("female:x-ray"))
        assertTrue(coordinator.commitTagInput("male:sole male"))
        assertTrue(coordinator.commitTagInput("-character:rin"))

        assertEquals(
            listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("the idolmaster", SearchFacet.SERIES, "parody"),
                SearchTerm("x-ray", SearchFacet.TAG, "female"),
                SearchTerm("sole male", SearchFacet.TAG, "male"),
            ),
            coordinator.draftQuery.includeTerms,
        )
        assertEquals(
            listOf(SearchTerm("rin", SearchFacet.CHARACTER, "character")),
            coordinator.draftQuery.excludeTerms,
        )
        assertEquals(
            FacetedSearchScope(SearchFacet.CHARACTER, "character"),
            coordinator.selectedSearchScope,
        )
    }

    @Test
    fun `unified mode blocks scoped input and removes historical source terms without excluding sources`() = runTest {
        val pixiv = RecordingAdapter(SourceKey.PIXIV)
        val gelbooru = RecordingAdapter(SourceKey.GELBOORU)
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                mapOf(SourceKey.PIXIV to pixiv, SourceKey.GELBOORU to gelbooru),
            ),
        )
        coordinator.initialize()

        assertFalse(coordinator.commitTagInput("artist:najar"))
        assertTrue(coordinator.tagInputValidationMessage?.contains("specific source") == true)
        assertTrue(coordinator.draftQuery.includeTerms.isEmpty())

        coordinator.applyHistoricalQuery(
            Query(
                mode = QueryMode.Unified,
                includeTerms = listOf(
                    SearchTerm("portable"),
                    SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                ),
                excludeTerms = listOf(
                    SearchTerm("series", SearchFacet.SERIES, "series"),
                ),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
        )

        assertEquals(listOf(SearchTerm("portable")), coordinator.appliedQuery.includeTerms)
        assertTrue(coordinator.appliedQuery.excludeTerms.isEmpty())
        assertTrue(coordinator.tagInputValidationMessage?.contains("removed") == true)
        assertEquals(listOf("portable"), pixiv.lastSearchQuery?.includeTags)
        assertEquals(listOf("portable"), gelbooru.lastSearchQuery?.includeTags)
        assertEquals(
            setOf(SourceKey.PIXIV, SourceKey.GELBOORU),
            coordinator.statuses
                .filter { status -> status.state == SourceRunState.SUCCESS }
                .mapTo(mutableSetOf()) { status -> status.source },
        )
    }

    @Test
    fun `nhentai filters inspect typed language and type terms without consuming artist terms`() = runTest {
        val coordinator = SearchCoordinator(
            registry = CompatibilityRegistry(
                mapOf(SourceKey.NHENTAI to RecordingAdapter(SourceKey.NHENTAI)),
            ),
        )
        coordinator.initialize()
        val numeric = SearchTerm("634609")
        val artistChinese = SearchTerm("chinese", SearchFacet.ARTIST, "artist")
        val artistFullColor = SearchTerm("full color", SearchFacet.ARTIST, "artist")
        val japanese = SearchTerm("japanese", SearchFacet.LANGUAGE, "language")
        val categoryFullColor = SearchTerm("full color", SearchFacet.TYPE, "category")
        val fullColor = SearchTerm("full color", SearchFacet.TAG, "tag")
        coordinator.applyHistoricalQuery(
            Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTerms = listOf(
                    numeric,
                    artistChinese,
                    artistFullColor,
                    japanese,
                    categoryFullColor,
                    fullColor,
                ),
                excludeTerms = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
        )

        assertEquals(NhentaiLanguageFilter.JAPANESE, coordinator.selectedNhentaiLanguageFilter())
        assertTrue(coordinator.selectedNhentaiFullColorFilter())
        assertNull(coordinator.directNhentaiGalleryIdCandidate())

        coordinator.setNhentaiLanguageFilter(NhentaiLanguageFilter.CHINESE)
        coordinator.setNhentaiFullColorFilter(false)

        assertTrue(artistChinese in coordinator.draftQuery.includeTerms)
        assertTrue(artistFullColor in coordinator.draftQuery.includeTerms)
        assertTrue(categoryFullColor in coordinator.draftQuery.includeTerms)
        assertFalse(japanese in coordinator.draftQuery.includeTerms)
        assertFalse(fullColor in coordinator.draftQuery.includeTerms)
        assertEquals(NhentaiLanguageFilter.CHINESE, coordinator.selectedNhentaiLanguageFilter())
        assertFalse(coordinator.selectedNhentaiFullColorFilter())

        coordinator.removeIncludeTerm(artistChinese)
        coordinator.removeIncludeTerm(artistFullColor)
        coordinator.removeIncludeTerm(categoryFullColor)
        assertEquals("634609", coordinator.directNhentaiGalleryIdCandidate())
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
    private val tagCountsByName: Map<String, Int> = emptyMap(),
) : SourceAdapter, TagCountLookupSourceAdapter {
    var lastSearchQuery: Query? = null
    var lastAutocompletePrefix: String? = null
    var batchTagLookupCount: Int = 0

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
        lastAutocompletePrefix = prefix
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

    override suspend fun fetchTagCounts(tags: List<String>): Map<String, Int> {
        if (tags.isEmpty()) return emptyMap()
        batchTagLookupCount += 1
        return tags.mapNotNull { raw ->
            val normalized = raw.trim().replace(' ', '_').lowercase()
            tagCountsByName[normalized]?.let { count -> normalized to count }
        }.toMap()
    }

    override suspend fun resolvePost(id: PostId): Post? = null
}

private class FacetedRecordingAdapter(
    override val sourceKey: SourceKey,
    override val supportedSearchScopes: Set<FacetedSearchScope>,
    private val suggestionsByScope: Map<FacetedSearchScope, List<FacetedTagSuggestion>> = emptyMap(),
    private val autocompleteFailure: Throwable? = null,
) : SourceAdapter, FacetedSearchSourceAdapter {
    var lastSearchQuery: Query? = null
    var lastFacetedPrefix: String? = null
    var lastFacetedScope: FacetedSearchScope? = null

    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportsSortNewest = true,
        supportsSortPopular = true,
        supportsSortTop = true,
        supportsSortRandom = true,
        supportsExcludeTagsServerSide = true,
        supportsDateRangeServerSide = false,
        supportsMinScoreServerSide = false,
        requiresCredentials = false,
    )

    override suspend fun search(query: Query, pageToken: String?): Page<Post> {
        lastSearchQuery = query
        return Page(items = emptyList(), nextPageToken = null)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> {
        return emptyList()
    }

    override suspend fun autocompleteFaceted(
        prefix: String,
        scope: FacetedSearchScope,
        limit: Int,
    ): List<FacetedTagSuggestion> {
        autocompleteFailure?.let { error -> throw error }
        lastFacetedPrefix = prefix
        lastFacetedScope = scope
        return suggestionsByScope[scope]
            .orEmpty()
            .filter { suggestion -> suggestion.text.contains(prefix, ignoreCase = true) }
            .take(limit)
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

private class RecordingFacetedSuggestionStore : TagSuggestionStore {
    val facetedSuggestions = mutableListOf<FacetedTagSuggestion>()

    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> = emptyList()

    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) = Unit

    override fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> = facetedSuggestions.take(limit)

    override fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) {
        facetedSuggestions += suggestions
    }
}

private class SearchResolveAdapter(
    override val sourceKey: SourceKey,
    private val searchResults: List<Post>,
    private val resolveBlock: suspend (PostId) -> Post?,
) : SourceAdapter {
    var resolveCallCount: Int = 0

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
        return Page(items = searchResults, nextPageToken = null)
    }

    override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()

    override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()

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
        resolveCallCount += 1
        return resolveBlock(id)
    }
}

private class InMemoryTagSuggestionStore : TagSuggestionStore {
    private val bySource = mutableMapOf<SourceKey, LinkedHashMap<String, TagSuggestion>>()

    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return bySource[source].orEmpty().values.take(limit)
    }

    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) {
        if (suggestions.isEmpty()) return
        val bucket = bySource.getOrPut(source) { linkedMapOf() }
        suggestions.forEach { suggestion ->
            val text = suggestion.text.trim()
            if (text.isBlank()) return@forEach
            bucket[text.lowercase()] = suggestion.copy(text = text)
        }
    }
}

private fun samplePost(
    source: SourceKey = SourceKey.IWARA,
    sourcePostId: String = "1",
): Post {
    return Post(
        id = PostId(source = source, sourcePostId = sourcePostId),
        preview = ImageRef(url = "https://i.iwara.tv/image/thumbnail/$sourcePostId/$sourcePostId.jpg", localPath = null, mime = "image/jpeg"),
        full = null,
        media = emptyList(),
        pageUrl = "https://www.iwara.tv/video/$sourcePostId",
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        title = "Sample",
    )
}
