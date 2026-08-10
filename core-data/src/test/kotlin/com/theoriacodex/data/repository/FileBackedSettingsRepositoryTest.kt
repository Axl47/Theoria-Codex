package com.theoriacodex.data.repository

import com.theoriacodex.data.testing.RecordingIoDispatcher
import com.theoriacodex.data.testing.ControllableIoDispatcher
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class FileBackedSettingsRepositoryTest : FileBackedRepositoryTestFixture() {
    @Test
    @Suppress("DEPRECATION") // Verifies the compatibility field remains readable during migration.
    fun `settings repository persists updates`() = runTest {
        val dir = tempDir("settings-store-")
        val first = FileBackedSettingsRepository(dir)
        first.setEnabledSources(setOf(SourceKey.PIXIV, SourceKey.GELBOORU))
        first.setSourceWeights(mapOf(SourceKey.PIXIV to 4.0, SourceKey.GELBOORU to 1.0))
        first.setCacheFullImageOnSave(true)
        first.setResolveUnknownAnimatedDurations(true)
        first.setInvertMultiImageScrollDirection(true)
        first.setScenarioPreset(ScenarioPreset.EMPTY_RESULTS)
        first.setLastTab("codex")
        first.setActiveProfile("profile-alt")

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertTrue(loaded.contentFilters.resolveUnknownAnimatedDurations)
        assertTrue(loaded.viewer.invertMultiImageScrollDirection)
        assertEquals(ScenarioPreset.EMPTY_RESULTS, loaded.scenarioPreset)
        assertEquals("codex", loaded.lastSelectedTabRoute)
        assertEquals("profile-alt", loaded.activeProfileId)
        val total = loaded.runtime.sourceWeights.values.sum()
        assertEquals(1.0, total, 0.0001)
    }

    @Test
    fun `settings repository persists provider health snapshots`() = runTest {
        val dir = tempDir("settings-provider-health-")
        val first = FileBackedSettingsRepository(dir)
        first.setProviderHealthSnapshots(
            listOf(
                ProviderHealthSnapshot(
                    source = SourceKey.GELBOORU,
                    status = ProviderHealthSnapshotStatus.OK,
                    checkedAtEpochMs = 123L,
                    latencyMs = 45L,
                    message = "Returned 2 posts",
                ),
                ProviderHealthSnapshot(
                    source = SourceKey.PIXIV,
                    status = ProviderHealthSnapshotStatus.FAILED,
                    checkedAtEpochMs = 124L,
                    failureReason = "AUTH_REQUIRED",
                    message = "Missing credentials",
                ),
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val health = second.observeSettings().first().providerHealth

        assertEquals(ProviderHealthSnapshotStatus.OK, health[SourceKey.GELBOORU]?.status)
        assertEquals(45L, health[SourceKey.GELBOORU]?.latencyMs)
        assertEquals("AUTH_REQUIRED", health[SourceKey.PIXIV]?.failureReason)
    }

    @Test
    fun `settings repository defaults unknown duration resolution off for old files`() = runTest {
        val dir = tempDir("settings-store-old-")
        dir.resolve("settings_store.json").writeText(
            """
                {
                  "enabledSources": ["PIXIV", "GELBOORU"],
                  "cacheFullImageOnSave": true
                }
            """.trimIndent(),
        )

        val loaded = FileBackedSettingsRepository(dir).observeSettings().first()

        assertTrue(loaded.cache.cacheFullImageOnSave)
        assertFalse(loaded.contentFilters.resolveUnknownAnimatedDurations)
        assertFalse(loaded.viewer.invertMultiImageScrollDirection)
    }

    @Test
    fun `legacy settings enable hitomi once and preserve a post-upgrade disable`() = runTest {
        val dir = tempDir("settings-source-catalog-migration-")
        dir.resolve("settings_store.json").writeText(
            """
                {
                  "enabledSources": ["PIXIV", "GELBOORU"],
                  "sourceWeights": {"PIXIV": 0.8, "GELBOORU": 0.2}
                }
            """.trimIndent(),
        )

        val repository = FileBackedSettingsRepository(dir)
        val migrated = repository.observeSettings().first()

        assertTrue(SourceKey.HITOMI in migrated.runtime.enabledSources)
        assertTrue(migrated.runtime.sourceWeights.containsKey(SourceKey.HITOMI))
        assertTrue(dir.resolve("settings_store.json").readText().contains("\"sourceCatalogVersion\": 2"))

        repository.setEnabledSources(migrated.runtime.enabledSources - SourceKey.HITOMI)

        val reloaded = FileBackedSettingsRepository(dir).observeSettings().first()
        assertFalse(SourceKey.HITOMI in reloaded.runtime.enabledSources)
    }

    @Test
    fun `settings repository persists dynamic recommendation profiles`() = runTest {
        val dir = tempDir("settings-profiles-")
        val first = FileBackedSettingsRepository(dir)
        val created = first.addRecommendationProfile("Anime Mood")
        assertTrue(
            first.addForYouBlacklistEntry(
                profileId = created.profileId,
                source = SourceKey.PIXIV,
                tags = listOf("portrait", "artist"),
            )
        )
        assertTrue(
            first.addFavoriteTag(
                profileId = created.profileId,
                source = SourceKey.GELBOORU,
                tag = "Blue Hair",
            )
        )
        first.setActiveProfile(created.profileId)

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()

        assertEquals(created.profileId, loaded.activeProfileId)
        assertTrue(loaded.recommendationProfiles.any { it.profileId == created.profileId && it.name == "Anime Mood" })
        assertEquals(1, loaded.forYouBlacklistByProfile[created.profileId].orEmpty().size)
        assertEquals(listOf("blue_hair"), loaded.favoriteTagsByProfile[created.profileId].orEmpty().map { it.tag })
        assertTrue(second.removeRecommendationProfile(created.profileId))
        val third = FileBackedSettingsRepository(dir)
        assertTrue(third.observeSettings().first().recommendationProfiles.none { it.profileId == created.profileId })
        assertTrue(third.observeSettings().first().forYouBlacklistByProfile[created.profileId].isNullOrEmpty())
        assertTrue(third.observeSettings().first().favoriteTagsByProfile[created.profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository persists for you blacklist entries`() = runTest {
        val dir = tempDir("settings-for-you-blacklist-")
        val first = FileBackedSettingsRepository(dir)
        val profileId = first.observeSettings().first().activeProfileId

        assertTrue(
            first.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("Cloud", "Sky"),
            )
        )
        assertFalse(
            first.addForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("sky", "cloud"),
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()
        assertEquals(listOf("cloud", "sky"), loaded.forYouBlacklistByProfile[profileId].orEmpty().first().tags)

        assertTrue(
            second.removeForYouBlacklistEntry(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tags = listOf("cloud", "sky"),
            )
        )
        val third = FileBackedSettingsRepository(dir)
        assertTrue(third.observeSettings().first().forYouBlacklistByProfile[profileId].isNullOrEmpty())
    }

    @Test
    fun `settings repository persists source-aware favorite tags`() = runTest {
        val dir = tempDir("settings-favorite-tags-")
        val first = FileBackedSettingsRepository(dir)
        val profileId = first.observeSettings().first().activeProfileId

        assertTrue(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "Blue Hair",
            )
        )
        assertFalse(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        assertTrue(
            first.addFavoriteTag(
                profileId = profileId,
                source = SourceKey.PIXIV,
                tag = "Blue Hair",
            )
        )

        val second = FileBackedSettingsRepository(dir)
        val loaded = second.observeSettings().first()
        assertEquals(listOf("blue_hair"), loaded.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.GELBOORU }.map { it.tag })
        assertEquals(listOf("Blue Hair"), loaded.favoriteTagsByProfile[profileId].orEmpty().filter { it.source == SourceKey.PIXIV }.map { it.tag })

        assertTrue(
            second.removeFavoriteTag(
                profileId = profileId,
                source = SourceKey.GELBOORU,
                tag = "blue_hair",
            )
        )
        val third = FileBackedSettingsRepository(dir)
        assertEquals(1, third.observeSettings().first().favoriteTagsByProfile[profileId].orEmpty().size)
        assertEquals(
            SourceKey.PIXIV,
            third.observeSettings().first().favoriteTagsByProfile[profileId].orEmpty().first().source,
        )
    }

}
