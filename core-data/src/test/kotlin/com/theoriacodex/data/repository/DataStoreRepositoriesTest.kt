package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableStorePhase
import com.theoriacodex.data.storage.sha256
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
    fun `settings imports legacy JSON once archives it and records a verifiable proof`() = runTest {
        val directory = tempFolder.newFolder("settings-import")
        val legacy = directory.resolve("settings_store.json")
        val legacyBytes = representativeLegacySettings().toByteArray()
        legacy.writeBytes(legacyBytes)
        val scope = newScope()
        val repository = DataStoreSettingsRepository(directory, scope)

        val settings = repository.observeSettings().first()

        assertEquals(setOf(SourceKey.PIXIV, SourceKey.GELBOORU), settings.runtime.enabledSources)
        assertTrue(settings.cache.cacheFullImageOnSave)
        assertTrue(settings.viewer.invertMultiImageScrollDirection)
        assertEquals("profile-custom", settings.activeProfileId)
        assertEquals(listOf("blue_hair"), settings.favoriteTagsByProfile.getValue("profile-custom").map { it.tag })
        assertFalse(legacy.exists())
        val archive = directory.resolve("settings_store.json.migrated-v3")
        assertTrue(archive.isFile)
        assertTrue(archive.readBytes().contentEquals(legacyBytes))
        assertTrue(directory.resolve(DATASTORE_SETTINGS_FILE_NAME).isFile)

        val status = repository.storageStatus.value
        assertEquals(DurableStorePhase.READY, status.phase)
        val proof = status.imports.single()
        assertEquals("settings_store.json", proof.sourceFileName)
        assertEquals(2, proof.sourceSchemaVersion)
        assertEquals(SETTINGS_DATASTORE_SCHEMA_VERSION, proof.destinationSchemaVersion)
        assertEquals(legacyBytes.sha256(), proof.sourceSha256)
        assertEquals(legacyBytes.size.toLong(), proof.sourceByteCount)
        assertEquals(1, proof.importedCounts["profiles"])

        closeScope(scope)
    }

    @Test
    fun `settings reconstruction uses DataStore state without replaying its retained archive`() = runTest {
        val directory = tempFolder.newFolder("settings-reconstruction")
        directory.resolve("settings_store.json").writeText(representativeLegacySettings())
        val firstScope = newScope()
        val first = DataStoreSettingsRepository(directory, firstScope)
        first.setCacheFullImageOnSave(false)
        first.setScenarioPreset(ScenarioPreset.SLOW_NETWORK)
        closeScope(firstScope)

        val secondScope = newScope()
        val reconstructed = DataStoreSettingsRepository(directory, secondScope)
        val settings = reconstructed.observeSettings().first()

        assertFalse(settings.cache.cacheFullImageOnSave)
        assertEquals(ScenarioPreset.SLOW_NETWORK, settings.scenarioPreset)
        assertEquals(1, reconstructed.storageStatus.value.imports.size)
        closeScope(secondScope)
    }

    @Test
    fun `an existing destination remains authoritative if stale legacy data appears later`() = runTest {
        val directory = tempFolder.newFolder("settings-stale-legacy")
        val firstScope = newScope()
        val first = DataStoreSettingsRepository(directory, firstScope)
        first.setScenarioPreset(ScenarioPreset.SLOW_NETWORK)
        closeScope(firstScope)
        val staleLegacy = directory.resolve("settings_store.json")
        staleLegacy.writeText(representativeLegacySettings())

        val secondScope = newScope()
        val reconstructed = DataStoreSettingsRepository(directory, secondScope)

        assertEquals(ScenarioPreset.SLOW_NETWORK, reconstructed.observeSettings().first().scenarioPreset)
        assertTrue(reconstructed.storageStatus.value.imports.isEmpty())
        assertTrue(staleLegacy.isFile)
        closeScope(secondScope)
    }

    @Test
    fun `minimal pre-catalog settings migrate hitomi and retain legacy defaults`() = runTest {
        val directory = tempFolder.newFolder("settings-pre-catalog")
        directory.resolve("settings_store.json").writeText(
            """{"enabledSources":["PIXIV"],"cacheFullImageOnSave":true}"""
        )
        val scope = newScope()
        val repository = DataStoreSettingsRepository(directory, scope)

        val settings = repository.observeSettings().first()

        assertEquals(setOf(SourceKey.PIXIV, SourceKey.HITOMI), settings.runtime.enabledSources)
        assertEquals(settings.runtime.enabledSources, settings.runtime.sourceWeights.keys)
        assertTrue(settings.cache.cacheFullImageOnSave)
        assertFalse(settings.contentFilters.resolveUnknownAnimatedDurations)
        assertEquals(1, repository.storageStatus.value.imports.single().sourceSchemaVersion)
        closeScope(scope)
    }

    @Test
    fun `settings corruption is backed up and deterministically replayed from the verified archive`() = runTest {
        val directory = tempFolder.newFolder("settings-corruption")
        directory.resolve("settings_store.json").writeText(representativeLegacySettings())
        val firstScope = newScope()
        DataStoreSettingsRepository(directory, firstScope).awaitReady()
        closeScope(firstScope)
        directory.resolve(DATASTORE_SETTINGS_FILE_NAME).writeText("{broken")

        val recoveredScope = newScope()
        val recovered = DataStoreSettingsRepository(directory, recoveredScope)
        val settings = recovered.observeSettings().first()

        assertTrue(settings.cache.cacheFullImageOnSave)
        assertEquals("profile-custom", settings.activeProfileId)
        assertTrue(directory.resolve("$DATASTORE_SETTINGS_FILE_NAME.corrupt").isFile)
        assertNotNull(recovered.storageStatus.value.corruptionRecovery)
        assertEquals(1, recovered.storageStatus.value.imports.size)
        closeScope(recoveredScope)
    }

    @Test
    fun `malformed settings migration retains the input and succeeds after a fixed retry`() = runTest {
        val directory = tempFolder.newFolder("settings-migration-retry")
        val legacy = directory.resolve("settings_store.json")
        legacy.writeText("{broken")
        val failedScope = newScope()
        val failed = DataStoreSettingsRepository(directory, failedScope)

        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { failed.awaitReady() } }
        assertTrue(legacy.isFile)
        assertEquals(DurableStorePhase.FAILED, failed.storageStatus.value.phase)
        closeScope(failedScope)

        legacy.writeText(representativeLegacySettings())
        val retryScope = newScope()
        val retry = DataStoreSettingsRepository(directory, retryScope)
        assertTrue(retry.observeSettings().first().cache.cacheFullImageOnSave)
        assertFalse(legacy.exists())
        closeScope(retryScope)
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
                    repository.addFavoriteTag(profile, SourceKey.PIXIV, "tag-$index")
                }
            }.awaitAll()
        }

        val tags = repository.observeSettings().first().favoriteTagsByProfile.getValue(profile).map { it.tag }.toSet()
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
    fun `settings and ui legacy migrations are safe in either concurrent initialization order`() = runTest {
        val directory = tempFolder.newFolder("concurrent-legacy-import")
        directory.resolve("settings_store.json").writeText(representativeLegacySettings())
        directory.resolve("ui_restore_store.json").writeText(
            """
            {
              "searchScrollStates": {
                "query": {"firstVisibleItemIndex": 4, "firstVisibleItemOffsetPx": 18}
              },
              "viewerLaunchContext": null
            }
            """.trimIndent()
        )
        val scope = newScope()
        val settings = DataStoreSettingsRepository(directory, scope)
        val uiRestore = DataStoreUiRestoreRepository(directory, scope)

        coroutineScope {
            awaitAll(
                async(Dispatchers.Default) { settings.awaitReady() },
                async(Dispatchers.Default) { uiRestore.awaitReady() },
            )
        }

        assertEquals("codex", uiRestore.getLastTab())
        assertEquals(4, uiRestore.getSearchScrollState("query")?.firstVisibleItemIndex)
        assertTrue(directory.resolve("settings_store.json.migrated-v3").isFile)
        assertTrue(directory.resolve("ui_restore_store.json.migrated-v2").isFile)
        assertEquals(
            setOf("settings_store.json", "ui_restore_store.json"),
            uiRestore.storageStatus.value.imports.map { proof -> proof.sourceFileName }.toSet(),
        )
        closeScope(scope)
    }

    @Test
    fun `ui migration records an explicit zero-count proof for blank legacy last-tab state`() = runTest {
        val directory = tempFolder.newFolder("ui-empty-legacy-tab")
        directory.resolve("settings_store.json").writeText(
            """{"sourceCatalogVersion":2,"lastSelectedTabRoute":"   "}"""
        )
        val firstScope = newScope()
        val first = DataStoreUiRestoreRepository(directory, firstScope)

        assertNull(first.getLastTab())
        val proof = first.storageStatus.value.imports.single()
        assertEquals("settings_store.json", proof.sourceFileName)
        assertEquals(0, proof.importedCounts["lastTab"])
        closeScope(firstScope)

        val secondScope = newScope()
        val reconstructed = DataStoreUiRestoreRepository(directory, secondScope)
        assertNull(reconstructed.getLastTab())
        assertEquals(1, reconstructed.storageStatus.value.imports.size)
        closeScope(secondScope)
    }

    @Test
    fun `ui restore reconstructs settings section expansion after reopening`() = runTest {
        val directory = tempFolder.newFolder("ui-settings-expansion")
        val firstScope = newScope()
        val first = DataStoreUiRestoreRepository(directory, firstScope)
        val expansion = mapOf(
            "UNIFIED_MODE" to false,
            "STORAGE_AND_CACHING" to false,
        )

        first.setSettingsSectionExpansion(expansion)
        closeScope(firstScope)

        val secondScope = newScope()
        val reconstructed = DataStoreUiRestoreRepository(directory, secondScope)

        assertEquals(expansion, reconstructed.getSettingsSectionExpansion())
        closeScope(secondScope)
    }

    @Test
    fun `ui restore migrates legacy query offsets once and removes the second store`() = runTest {
        val directory = tempFolder.newFolder("ui-query-scroll-migration")
        directory.resolve(DATASTORE_UI_RESTORE_FILE_NAME).writeText(
            """
            {
              "schemaVersion": 2,
              "state": {
                "searchScrollStates": {
                  "existing": {"firstVisibleItemIndex": 6, "firstVisibleItemOffsetPx": 60}
                }
              },
              "legacyImports": []
            }
            """.trimIndent(),
        )
        val queryFile = directory.resolve("query_store.json")
        queryFile.writeText(
            """
            {
              "queries": {"unified": {"modeType": "unified", "sort": "TOP"}},
              "scrollOffsets": {"legacy": 27, "existing": 999}
            }
            """.trimIndent(),
        )
        val firstScope = newScope()
        val first = DataStoreUiRestoreRepository(directory, firstScope)

        assertEquals(SearchScrollState(0, 27), first.getSearchScrollState("legacy"))
        assertEquals(SearchScrollState(6, 60), first.getSearchScrollState("existing"))
        assertFalse(queryFile.readText().contains("scrollOffsets"))
        assertTrue(queryFile.readText().contains("\"queries\""))
        val proof = first.storageStatus.value.imports.single()
        assertEquals("query_store.json", proof.sourceFileName)
        assertEquals(2, proof.importedCounts["searchScrollStates"])
        closeScope(firstScope)

        val secondScope = newScope()
        val reopened = DataStoreUiRestoreRepository(directory, secondScope)

        assertEquals(SearchScrollState(0, 27), reopened.getSearchScrollState("legacy"))
        assertEquals(1, reopened.storageStatus.value.imports.size)
        assertFalse(queryFile.readText().contains("scrollOffsets"))
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
    fun `ui restore corruption without legacy input recovers to an explicit empty state`() = runTest {
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
