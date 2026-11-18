package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.DurableStorePhase
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreRepositoriesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `viewer preferences survive a fresh settings repository`() = runTest {
        val directory = tempFolder.newFolder("viewer-play-mode")
        val firstScope = newScope()
        try {
            DataStoreSettingsRepository(directory, firstScope).updateSettings { current ->
                current.copy(oldSearchBar = true, codexLibrarySort = CodexLibrarySort.MOST_ITEMS, viewer = current.viewer.copy(
                    playbackMode = com.theoriacodex.domain.model.ViewerPlaybackMode.ONCE,
                    instantSeek = true,
                    commentOrder = com.theoriacodex.domain.model.CommentOrder.NEWEST,
                ))
            }
        } finally {
            closeScope(firstScope)
        }
        val secondScope = newScope()
        try {
            val restored = DataStoreSettingsRepository(directory, secondScope).observeSettings().first()
            assertEquals(com.theoriacodex.domain.model.ViewerPlaybackMode.ONCE, restored.viewer.playbackMode)
            assertTrue(restored.viewer.instantSeek)
            assertEquals(com.theoriacodex.domain.model.CommentOrder.NEWEST, restored.viewer.commentOrder)
            assertTrue(restored.oldSearchBar)
            assertEquals(CodexLibrarySort.MOST_ITEMS, restored.codexLibrarySort)
        } finally {
            closeScope(secondScope)
        }
    }

    @Test
    fun `constructors perform no file reads or writes`() {
        val directory = tempFolder.newFolder("constructor-no-io")
        val scope = newScope()

        DataStoreSettingsRepository(directory, scope)
        DataStoreUiRestoreRepository(directory, scope)
        DataStoreStatisticsRepository(directory, scope)

        assertTrue(directory.listFiles().orEmpty().isEmpty())
        scope.cancel()
    }

    @Test
    fun `source weight memory survives repository reconstruction`() = runTest {
        val directory = tempFolder.newFolder("weight-memory")
        val firstScope = newScope()
        val first = DataStoreSettingsRepository(directory, firstScope)
        val pair = setOf(SourceKey.PIXIV, SourceKey.GELBOORU)
        first.setEnabledSources(pair)
        first.setSourceWeights(mapOf(SourceKey.PIXIV to 4.0, SourceKey.GELBOORU to 1.0))
        first.setEnabledSources(setOf(SourceKey.HITOMI))
        closeScope(firstScope)

        val secondScope = newScope()
        try {
            val second = DataStoreSettingsRepository(directory, secondScope)
            second.setEnabledSources(pair)
            val weights = second.observeSettings().first().runtime.sourceWeights
            assertEquals(0.8, weights.getValue(SourceKey.PIXIV), 0.0000001)
            assertEquals(0.2, weights.getValue(SourceKey.GELBOORU), 0.0000001)
        } finally {
            closeScope(secondScope)
        }
    }

    @Test
    fun `settings ignore unknown OCR languages without resetting known preferences`() = runTest {
        val directory = tempFolder.newFolder("settings-unknown-ocr-language")
        directory.resolve(DATASTORE_SETTINGS_FILE_NAME).writeText(
            """
            {
              "schemaVersion": 3,
              "settings": {
                "viewerAutomaticTextTranslationEnabled": true,
                "viewerEnabledOcrLanguages": ["JAPANESE", "FUTURE_SCRIPT"]
              },
              "legacyImports": []
            }
            """.trimIndent(),
        )
        val scope = newScope()
        val settings = DataStoreSettingsRepository(directory, scope).observeSettings().first()

        assertTrue(settings.viewer.automaticTextTranslationEnabled)
        assertEquals(setOf(ViewerOcrLanguage.JAPANESE), settings.viewer.enabledOcrLanguages)
        closeScope(scope)
    }

    @Test
    fun `retired legacy files and import proofs are ignored without resetting either store`() = runTest {
        val directory = tempFolder.newFolder("retired-legacy-imports")
        // Proof entries written by the retired importers; this one would have failed their validation.
        val proofs = """[{"sourceFileName":"settings_store.json","sourceSchemaVersion":2,""" +
            """"destinationSchemaVersion":9,"sourceSha256":"not-a-hash","importedCounts":{"profiles":1}}]"""
        directory.resolve(DATASTORE_SETTINGS_FILE_NAME).writeText(
            """{"schemaVersion":3,"settings":{"scenarioPreset":"SLOW_NETWORK"},"legacyImports":$proofs}"""
        )
        directory.resolve(DATASTORE_UI_RESTORE_FILE_NAME).writeText(
            """{"schemaVersion":2,"state":{"lastTab":"search"},"legacyImports":$proofs}"""
        )
        val untouchedFiles = mapOf(
            "settings_store.json" to representativeLegacySettings(),
            "ui_restore_store.json" to """{"lastTab":"codex"}""",
            "query_store.json" to """{"queries":{},"scrollOffsets":{"legacy":27}}""",
        )
        untouchedFiles.forEach { (name, text) -> directory.resolve(name).writeText(text) }
        val scope = newScope()
        val settings = DataStoreSettingsRepository(directory, scope)
        val uiRestore = DataStoreUiRestoreRepository(directory, scope)

        assertEquals(ScenarioPreset.SLOW_NETWORK, settings.observeSettings().first().scenarioPreset)
        assertEquals("search", uiRestore.getLastTab())
        assertNull(uiRestore.getSearchScrollState("legacy"))
        listOf(settings.storageStatus.value, uiRestore.storageStatus.value).forEach { status ->
            assertEquals(DurableStorePhase.READY, status.phase)
            assertNull(status.corruptionRecovery)
        }
        untouchedFiles.forEach { (name, text) -> assertEquals(text, directory.resolve(name).readText()) }
        assertEquals(
            untouchedFiles.keys + DATASTORE_SETTINGS_FILE_NAME + DATASTORE_UI_RESTORE_FILE_NAME,
            directory.list().orEmpty().toSet(),
        )
        closeScope(scope)
    }
    @Test
    fun `minimal pre-catalog settings gain every later source and retain legacy defaults`() = runTest {
        val directory = tempFolder.newFolder("settings-pre-catalog")
        directory.resolve(DATASTORE_SETTINGS_FILE_NAME).writeText(
            """{"schemaVersion":3,"settings":{"enabledSources":["PIXIV"],"cacheFullImageOnSave":true}}"""
        )
        val scope = newScope()
        val repository = DataStoreSettingsRepository(directory, scope)

        val settings = repository.observeSettings().first()

        assertEquals(
            setOf(SourceKey.PIXIV, SourceKey.HITOMI, SourceKey.DANBOORU, SourceKey.AIBOORU),
            settings.runtime.enabledSources,
        )
        assertEquals(settings.runtime.enabledSources, settings.runtime.sourceWeights.keys)
        assertTrue(settings.cache.cacheFullImageOnSave)
        assertFalse(settings.contentFilters.resolveUnknownAnimatedDurations)
        closeScope(scope)
    }
    @Test
    fun `settings corruption is backed up and resets to defaults`() = runTest {
        val directory = tempFolder.newFolder("settings-corruption")
        directory.resolve(DATASTORE_SETTINGS_FILE_NAME).writeText("{broken")
        val scope = newScope()
        val recovered = DataStoreSettingsRepository(directory, scope)
        val settings = recovered.observeSettings().first()

        assertEquals(AppSettings().scenarioPreset, settings.scenarioPreset)
        assertTrue(directory.resolve("$DATASTORE_SETTINGS_FILE_NAME.corrupt").isFile)
        assertNotNull(recovered.storageStatus.value.corruptionRecovery)
        closeScope(scope)
    }
    @Test
    fun `newer settings schema fails closed without corruption replacement`() = runTest {
        val directory = tempFolder.newFolder("settings-future-schema")
        val destination = directory.resolve(DATASTORE_SETTINGS_FILE_NAME)
        val future = """{"schemaVersion":99,"settings":{},"legacyImports":[]}"""
        destination.writeText(future)
        val scope = newScope()
        val repository = DataStoreSettingsRepository(directory, scope)

        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { repository.awaitReady() } }

        assertEquals(future, destination.readText())
        assertFalse(directory.resolve("$DATASTORE_SETTINGS_FILE_NAME.corrupt").exists())
        assertEquals(DurableStorePhase.FAILED, repository.storageStatus.value.phase)
        closeScope(scope)
    }

    @Test
    fun `concurrent settings mutations are serialized without lost profile state`() = runTest {
        val directory = tempFolder.newFolder("settings-concurrency")
        val scope = newScope()
        val repository = DataStoreSettingsRepository(directory, scope)
        val profile = repository.observeSettings().first().activeProfileId

        coroutineScope {
            (0 until 40).map { index ->
                async(Dispatchers.Default) {
                    repository.addFavoriteTag(profile, SourceKey.PIXIV, listOf("tag-$index"))
                }
            }.awaitAll()
        }

        val tags = repository.observeSettings().first().favoriteTagsByProfile.getValue(profile).map { it.tags.single() }.toSet()
        assertEquals((0 until 40).map { index -> "tag-$index" }.toSet(), tags)
        closeScope(scope)
    }

    @Test
    fun `settings persistence policy bounds growing profile-owned collections`() {
        val profileId = "profile-0"
        val normalized = normalizeDataStoreSettings(
            AppSettings(
                recommendationProfiles = (0 until 80).map { index ->
                    RecommendationProfile(profileId = "profile-$index", name = "Profile $index")
                },
                activeProfileId = "profile-79",
                favoriteTagsByProfile = mapOf(
                    profileId to (0 until 600).map { index -> FavoriteTagEntry(SourceKey.PIXIV, "tag-$index") },
                ),
                forYouBlacklistByProfile = mapOf(
                    profileId to (0 until 300).map { index ->
                        ForYouBlacklistEntry(SourceKey.PIXIV, listOf("tag-$index"))
                    },
                ),
            )
        )

        assertEquals(MAX_PERSISTED_RECOMMENDATION_PROFILES, normalized.recommendationProfiles.size)
        assertTrue(normalized.recommendationProfiles.any { profile -> profile.profileId == "profile-79" })
        assertEquals(
            MAX_PERSISTED_FAVORITE_TAGS_PER_PROFILE,
            normalized.favoriteTagsByProfile.getValue(profileId).size,
        )
        assertEquals(
            MAX_PERSISTED_BLACKLIST_ENTRIES_PER_PROFILE,
            normalized.forYouBlacklistByProfile.getValue(profileId).size,
        )
    }

    @Test
    fun `retired expansion field loads without recovery or losing restore state`() = runTest {
        val payload = """{
            "lastTab":"codex",
            "searchScrollStates":{"query":{"firstVisibleItemIndex":4,"firstVisibleItemOffsetPx":12}},
            "settingsSectionExpansion":{"UNIFIED_MODE":false},
            "feedFabRestoreStates":{"search":{"animatedOnly":true}},
            "viewerLaunchContext":{"streamSource":"SEARCH","queryHash":"query","startIndex":2}
        }"""
        val directory = tempFolder.newFolder("retired-expansion")
        val file = directory.resolve(DATASTORE_UI_RESTORE_FILE_NAME)
        val original = """{"schemaVersion":2,"state":$payload}"""
        file.writeText(original)
        val scope = newScope()
        val repository = DataStoreUiRestoreRepository(directory, scope)
        assertEquals("codex", repository.getLastTab())
        assertEquals(SearchScrollState(4, 12), repository.getSearchScrollState("query"))
        assertTrue(repository.getFeedFabRestoreStates().getValue("search").animatedOnly)
        assertEquals(2, repository.observeViewerLaunchContext().first()?.startIndex)
        assertNull(repository.storageStatus.value.corruptionRecovery)
        assertEquals(original, file.readText())
        repository.setLastTab("search")
        closeScope(scope)
        val reopenedScope = newScope()
        val reopened = DataStoreUiRestoreRepository(directory, reopenedScope)
        assertEquals("search", reopened.getLastTab())
        assertEquals(SearchScrollState(4, 12), reopened.getSearchScrollState("query"))
        assertTrue(reopened.getFeedFabRestoreStates().getValue("search").animatedOnly)
        assertEquals(2, reopened.observeViewerLaunchContext().first()?.startIndex)
        assertNull(reopened.storageStatus.value.corruptionRecovery)
        assertFalse(file.readText().contains("settingsSectionExpansion"))
        closeScope(reopenedScope)
    }
    @Test
    fun `older feed FAB records include all saved searches by default`() {
        val state = DurableJson.decodeFromString<FeedFabRestoreState>("""{"animatedOnly":true}""")
        assertTrue(state.animatedOnly)
        assertTrue(state.excludedSavedSearches.isEmpty())
    }

    @Test
    fun `ui restore reconstructs independent feed FAB contexts after reopening`() = runTest {
        val directory = tempFolder.newFolder("ui-feed-fab-contexts")
        val firstScope = newScope()
        val first = DataStoreUiRestoreRepository(directory, firstScope)
        val search = FeedFabRestoreState(animatedOnly = true, hideLiked = true)
        val codex = FeedFabRestoreState(source = "PIXIV", language = "JAPANESE", sortMode = "OLDEST_SAVED",
            followedSources = listOf("PIXIV", "IWARA"), followedAuthors = listOf("PIXIV:1", "IWARA:1"))

        val saved = FeedFabRestoreState(excludedSavedSearches = listOf("pin-a", "pin-b"))
        first.setFeedFabRestoreState("codex:system:saved-searches", saved)
        first.setFeedFabRestoreState("search", search)
        first.setFeedFabRestoreState("codex:one", codex)
        closeScope(firstScope)

        val secondScope = newScope()
        val reconstructed = DataStoreUiRestoreRepository(directory, secondScope)

        assertEquals(mapOf("search" to search, "codex:one" to codex, "codex:system:saved-searches" to saved), reconstructed.getFeedFabRestoreStates())
        closeScope(secondScope)
    }

    @Test
    fun `newer ui schema fails closed without corruption replacement`() = runTest {
        val directory = tempFolder.newFolder("ui-future-schema")
        val destination = directory.resolve(DATASTORE_UI_RESTORE_FILE_NAME)
        val future = """{"schemaVersion":99,"state":{},"legacyImports":[]}"""
        destination.writeText(future)
        val scope = newScope()
        val repository = DataStoreUiRestoreRepository(directory, scope)

        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { repository.awaitReady() } }

        assertEquals(future, destination.readText())
        assertFalse(directory.resolve("$DATASTORE_UI_RESTORE_FILE_NAME.corrupt").exists())
        assertEquals(DurableStorePhase.FAILED, repository.storageStatus.value.phase)
        closeScope(scope)
    }

    @Test
    fun `concurrent ui mutations preserve independent scroll entries`() = runTest {
        val directory = tempFolder.newFolder("ui-concurrency")
        val scope = newScope()
        val repository = DataStoreUiRestoreRepository(directory, scope)

        coroutineScope {
            (0 until 40).map { index ->
                async(Dispatchers.Default) {
                    repository.setSearchScrollState(
                        "query-$index",
                        SearchScrollState(index, index * 3),
                    )
                }
            }.awaitAll()
        }

        (0 until 40).forEach { index ->
            assertEquals(index, repository.getSearchScrollState("query-$index")?.firstVisibleItemIndex)
        }
        closeScope(scope)
    }

    @Test
    fun `ui restore bounds and reconstructs the most recent scroll states`() = runTest {
        val directory = tempFolder.newFolder("ui-scroll-bound")
        val firstScope = newScope()
        val first = DataStoreUiRestoreRepository(directory, firstScope)
        val launch = ViewerLaunchContext(
            queryHash = "recents:watched",
            startIndex = 124,
            streamSource = ViewerStreamSource.RECENTS,
            scrollOffsetHint = 15,
            recentsSection = RecentPostSection.WATCHED,
        )
        first.setLastTab("settings")
        first.setViewerLaunchContext(launch)

        (0 until 125).forEach { index ->
            first.setSearchScrollState(
                "query-$index",
                SearchScrollState(firstVisibleItemIndex = index, firstVisibleItemOffsetPx = index * 2),
            )
        }
        assertNull(first.getSearchScrollState("query-0"))
        assertEquals(124, first.getSearchScrollState("query-124")?.firstVisibleItemIndex)
        closeScope(firstScope)

        val secondScope = newScope()
        val reconstructed = DataStoreUiRestoreRepository(directory, secondScope)
        assertEquals("settings", reconstructed.getLastTab())
        assertEquals(launch, reconstructed.observeViewerLaunchContext().first())
        assertNull(reconstructed.getSearchScrollState("query-24"))
        assertEquals(25, reconstructed.getSearchScrollState("query-25")?.firstVisibleItemIndex)
        assertEquals(124, reconstructed.getSearchScrollState("query-124")?.firstVisibleItemIndex)
        closeScope(secondScope)
    }

    @Test
    fun `ui restore corruption recovers to an explicit empty state`() = runTest {
        val directory = tempFolder.newFolder("ui-corruption")
        directory.resolve(DATASTORE_UI_RESTORE_FILE_NAME).writeText("not-json")
        val scope = newScope()
        val repository = DataStoreUiRestoreRepository(directory, scope)

        assertNull(repository.getLastTab())
        assertNull(repository.observeViewerLaunchContext().first())
        assertNotNull(repository.storageStatus.value.corruptionRecovery)
        assertTrue(directory.resolve("$DATASTORE_UI_RESTORE_FILE_NAME.corrupt").isFile)
        closeScope(scope)
    }

    private fun representativeLegacySettings(): String {
        return """
            {
              "sourceCatalogVersion": 2,
              "enabledSources": ["PIXIV", "GELBOORU"],
              "sourceWeights": {"PIXIV": 0.8, "GELBOORU": 0.2},
              "cacheFullImageOnSave": true,
              "resolveUnknownAnimatedDurations": true,
              "invertMultiImageScrollDirection": true,
              "scenarioPreset": "EMPTY_RESULTS",
              "lastSelectedTabRoute": "codex",
              "recommendationProfiles": [
                {"profileId": "profile-custom", "name": "Custom"}
              ],
              "activeProfileId": "profile-custom",
              "favoriteTagsByProfile": {
                "profile-custom": [{"source": "GELBOORU", "tag": "Blue Hair"}]
              },
              "forYouBlacklistByProfile": {
                "profile-custom": [{"source": "PIXIV", "tags": ["Night", "Cat"]}]
              },
              "providerHealth": []
            }
        """.trimIndent()
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun closeScope(scope: CoroutineScope) {
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
