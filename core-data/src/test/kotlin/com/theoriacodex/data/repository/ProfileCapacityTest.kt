package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileCapacityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `profile favorite and blacklist overflow preserve the complete durable state`() = runTest {
        val directory = temporary.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val expected = fullSettings(64).copy(
            favoriteTagsByProfile = mapOf("p0" to List(500) { FavoriteTagEntry(SourceKey.PIXIV, "tag-$it") }),
            forYouBlacklistByProfile = mapOf("p0" to List(250) { ForYouBlacklistEntry(SourceKey.PIXIV, listOf("seed-$it")) }),
        )
        try {
            val settings = DataStoreSettingsRepository(directory, scope)
            settings.updateSettings { expected }
            assertTrue(runCatching { settings.addRecommendationProfile("Overflow") }.isFailure)
            assertTrue(runCatching { settings.addFavoriteTag("p0", SourceKey.PIXIV, "overflow") }.isFailure)
            assertTrue(runCatching { settings.addForYouBlacklistEntry("p0", SourceKey.PIXIV, listOf("overflow")) }.isFailure)
            assertEquals(expected, settings.observeSettings().first())
        } finally { scope.cancel(); scope.coroutineContext.job.join() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(expected, DataStoreSettingsRepository(directory, reopenedScope).observeSettings().first())
        } finally { reopenedScope.cancel(); reopenedScope.coroutineContext.job.join() }
    }

    @Test fun `restore checks combined capacity before mutation and last available slot survives reopen`() = runTest {
        val directory = temporary.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val library = RecordingLibrary()
        val journal = directory.resolve("restore.json")
        val importedSettings = AppSettings(
            recommendationProfiles = listOf(RecommendationProfile("incoming", "Incoming")), activeProfileId = "incoming",
        )
        val backup = ProfileBackup(1L, importedSettings, BackupLibrary(
            listOf(Codex(ProfileLibraryIds.codex("incoming", "one"), "Imported", 1L)),
            emptyList(), emptyList(), emptyList(),
        ))
        try {
            val settings = DataStoreSettingsRepository(directory, scope)
            settings.updateSettings { fullSettings(64) }
            val service = ProfileBackupService(settings, library, InMemorySavedSearchRepository(),
                InMemoryReadingPositionRepository(), journal, newOperationId = { "capacity" })
            assertTrue(runCatching { service.restore(ProfileBackupCodec().encode(backup)) }.isFailure)
            assertFalse(journal.exists())
            assertEquals(0, library.merges)
            assertEquals(fullSettings(64), settings.observeSettings().first())

            settings.removeRecommendationProfile("p63")
            assertEquals(1, service.restore(ProfileBackupCodec().encode(backup)).profilesAdded)
            assertFalse(journal.exists())
            assertEquals(64, settings.observeSettings().first().recommendationProfiles.size)
        } finally { scope.cancel(); scope.coroutineContext.job.join() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = DataStoreSettingsRepository(directory, reopenedScope)
            assertTrue(settings.observeSettings().first().recommendationProfiles.any { it.profileId == "restored-capacity-0" })
            val service = ProfileBackupService(settings, library, InMemorySavedSearchRepository(),
                InMemoryReadingPositionRepository(), journal)
            assertEquals(1, ProfileBackupCodec().decode(service.export()).library.codices.size)
        } finally { reopenedScope.cancel(); reopenedScope.coroutineContext.job.join() }
    }

    private fun fullSettings(count: Int) = RepositoryPolicies.normalizeSettings(AppSettings(
        recommendationProfiles = List(count) { RecommendationProfile("p$it", "Profile $it") }, activeProfileId = "p0",
    ))

    private class RecordingLibrary : ProfileBackupStore {
        var merges = 0
        private var library = BackupLibrary(emptyList(), emptyList(), emptyList(), emptyList())
        override suspend fun snapshot(includeRecents: Boolean) = library
        override suspend fun merge(library: BackupLibrary) { merges++; this.library = library }
    }
}
