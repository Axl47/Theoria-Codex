package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.assertStoredFormat
import org.junit.Test

class DataStoreFormatTest {
    @Test
    fun `settings file keeps every stored key`() {
        val settings = SettingsStoreRecord(
            followedCreators = listOf(
                FollowedCreatorRecord(
                    "PIXIV", "Artist & <co>", "3", "https://www.pixiv.net/users/3", "user:3", "follow-1",
                    listOf("seen"), listOf("latest"), 9L, 10L,
                ),
            ),
            sourceCatalogVersion = 3,
            enabledSources = listOf("PIXIV", "custom_a_example"),
            sourceWeights = mapOf("PIXIV" to 0.7, "custom_a_example" to 1.0),
            sourceWeightsByConfiguration = mapOf("PIXIV" to mapOf("PIXIV" to 1.0)),
            viewerPlaybackMode = "ONCE",
            videoQuality = "HIGH",
            downloadQuality = "BEST",
            downloadsOverMetered = false,
            downloadsOverRoaming = true,
            cacheFullImageOnSave = true,
            resolveUnknownAnimatedDurations = true,
            invertMultiImageScrollDirection = true,
            oldSearchBar = true,
            seriesUpdateInterval = "HOURS_6",
            seriesDeleteAfterReading = true,
            codexLibrarySort = "NAME",
            instantSeek = true,
            viewerOrientationLock = "LANDSCAPE",
            viewerCommentOrder = "NEWEST",
            recommendationProfiles = listOf(RecommendationProfileRecord("profile-main", "Main = 1")),
            activeProfileId = "profile-main",
            forYouBlacklistByProfile = mapOf("profile-main" to listOf(ForYouBlacklistEntryRecord("PIXIV", listOf("x")))),
            favoriteTagsByProfile = mapOf(
                "profile-main" to listOf(FavoriteTagEntryRecord("GELBOORU", "cat_ears", listOf("cat_ears", "fox_ears"), "Ears")),
            ),
            providerHealth = listOf(ProviderHealthSnapshotRecord("PIXIV", "DEGRADED", 1L, 2L, "timeout", "slow")),
        )
        val full = SettingsDataStoreFile(settings = settings)
        assertStoredFormat(golden("settings-full.json") to full, retiredKeys = setOf("settings.scenarioPreset", "settings.viewerAutoRotateVideos"))
    }

    @Test
    fun `ui restore file keeps every stored key`() {
        val feedFilters = FeedFabRestoreState(
            excludedSavedSearches = listOf("saved"),
            followedSources = listOf("PIXIV"),
            followedAuthors = listOf("author"),
            animatedOnly = true,
            hideLiked = true,
            hideSaved = true,
            hideWatched = true,
            durationMinBucket = 2,
            durationMaxBucket = 20,
            sortMode = "NEWEST",
            source = "PIXIV",
            language = "english",
            fullColorOnly = true,
            grid = true,
            searchAnimationByScope = mapOf("scope" to SearchAnimationFilterRestoreState(1, 9)),
        )
        val state = UiRestoreStoreRecord(
            lastTab = "codex",
            searchScrollStates = mapOf("h" to SearchScrollStateRecord(7, 12)),
            feedFabRestoreStates = mapOf("ctx" to feedFilters),
            viewerLaunchContext = ViewerLaunchContextRecord("q", 4, "CODEX", 21, "CODEX"),
            librarySection = "SAVED",
            savedSearchNewPosts = SavedSearchNewPosts(mapOf("saved" to 5L), mapOf("saved" to NewPostMarks(listOf("PIXIV:1"), listOf("PIXIV:2", "PIXIV:1"), 4L))),
        )
        val full = UiRestoreDataStoreFile(state = state)
        assertStoredFormat(
            golden("ui-restore-full.json") to full,
            golden("ui-restore-switch-source.json") to UiRestoreDataStoreFile(state = UiRestoreStoreRecord(switchSourceSkipped = listOf("custom_mihon_7"))),
            retiredKeys = setOf("state.feedFabRestoreStates{}.searchAnimationByScope{}.animatedOnly"),
        )
    }

    @Test
    fun `statistics file keeps every stored key`() {
        val statistics = StatisticsStoreRecord(
            history = mapOf(
                "2026-11-02" to DailyActivity(
                    appOpenCount = 1L, totalForegroundMs = 2L, browsingMs = 3L, watchingMs = 4L, codexMs = 5L,
                    watchedPostCount = 6L, searchCount = 7L,
                ),
            ),
            codexStatistics = mapOf(
                "codex" to CodexStatistics(5L, 6L, mapOf("PIXIV:1" to CodexPostStatistics("Label & <x>", 1L, 2L, 3L))),
            ),
            appOpenCount = 1L,
            totalForegroundMs = 2L,
            browsingMs = 3L,
            watchingMs = 4L,
            codexMs = 5L,
            watchedPostCount = 6L,
            watchedBySource = mapOf("PIXIV" to 3L),
            watchedByTag = listOf(StatisticsTagCountRecord("PIXIV", "cat", 2L)),
            searchCount = 7L,
            searchesBySource = mapOf("GELBOORU" to 4L),
            searchesByTag = listOf(StatisticsTagCountRecord("GELBOORU", "dog", 1L)),
            searchesByTagSeeded = true,
            searchesByTypedTag = listOf(StatisticsTypedTagCountRecord("PIXIV", "a", "ARTIST", "artist", 1L)),
            searchesByTypedTagSeeded = true,
            forYouSearchCount = 8L,
            forYouSaveCount = 9L,
            postUrlCopyCount = 10L,
            codexEntryCounts = mapOf("codex" to 11L),
        )
        val full = StatisticsDataStoreFile(schemaVersion = 2, statistics = statistics)
        val series = StatisticsDataStoreFile(
            schemaVersion = 2,
            statistics = StatisticsStoreRecord(
                history = mapOf(
                    "2026-11-23" to DailyActivity(appOpenCount = 1L, totalForegroundMs = 30L, readingMs = 10L, animeMs = 20L),
                ),
                appOpenCount = 1L,
                totalForegroundMs = 30L,
                readingMs = 10L,
                animeMs = 20L,
            ),
        )
        val floors = StatisticsDataStoreFile(
            statistics = StatisticsStoreRecord(
                watchedPostCount = 40L,
                watchedBySource = mapOf("GELBOORU" to 40L),
                watchedByTag = listOf(StatisticsTagCountRecord("GELBOORU", "blush", 9L)),
                watchedTagFloors = mapOf("GELBOORU" to 4L),
            ),
        )
        assertStoredFormat(
            golden("statistics-full.json") to full,
            golden("statistics-series.json") to series,
            golden("statistics-floors.json") to floors,
        )
    }

    @Test
    fun `site profile file keeps every stored key`() {
        val site = SiteProfileRecord(
            "custom_a_example", "https://a.example", "A & <b>", "GELBOORU_02", 5, "Agent", "https://a.example/",
            true, 1L, 2L, "https://a.example/icon.png",
        )
        val full = SiteProfileStoreFile(sites = listOf(site, null))
        assertStoredFormat(golden("sites-full.json") to full)
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/datastore/$name")) { "missing golden $name" }.readText()
}
