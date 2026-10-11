package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AtomicJsonFileStore
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileDeletionServiceTest {
    @Test fun `retired profile checks execute inside Settings writes without affecting another profile`() = runTest {
        val directory = Files.createTempDirectory("retired-profile-settings").toFile()
        try {
            val guard = ProfileLibraryWriteGuard()
            val settings = DataStoreSettingsRepository(directory, backgroundScope, profileWriteGuard = guard)
            settings.awaitReady()
            val profile = settings.addRecommendationProfile("Retired")
            val before = settings.observeSettings().first()
            guard.retire(profile.profileId)
            assertTrue(runCatching { settings.addFavoriteTag(profile.profileId,
                com.theoriacodex.domain.model.SourceKey.PIXIV, "tag") }.isFailure)
            assertTrue(runCatching { settings.setActiveProfile(profile.profileId) }.isFailure)
            org.junit.Assert.assertEquals(before, settings.observeSettings().first())
            assertTrue(settings.addFavoriteTag("profile-main", com.theoriacodex.domain.model.SourceKey.PIXIV, "tag"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `a failure after library commit retains intent and a fresh owner completes Settings removal`() = runTest {
        val directory = Files.createTempDirectory("profile-deletion").toFile()
        try {
            val stored = InMemorySettingsRepository()
            val profile = stored.addRecommendationProfile("Remove")
            val library = InMemoryCodexLikesTransactions()
            val other = library.codices.ensureCodex(ProfileLibraryIds.codex("profile-main", "keep"), "Keep")
            val removed = library.codices.ensureCodex(ProfileLibraryIds.codex(profile.profileId, "remove"), "Remove")
            var failSettings = true
            val settings = object : SettingsRepository by stored {
                override suspend fun removeRecommendationProfile(profileId: String): Boolean {
                    if (failSettings) error("Settings disk unavailable")
                    return stored.removeRecommendationProfile(profileId)
                }
            }
            val journal = directory.resolve("intent.json")
            val dispatcher = StandardTestDispatcher(testScheduler)
            val guard = ProfileLibraryWriteGuard()
            val owner = ProfileDeletionService(settings, library, journal, guard,
                AtomicJsonFileStore(ioDispatcher = dispatcher), dispatcher)
            assertTrue(runCatching { owner.removeProfile(profile.profileId) }.exceptionOrNull() is ProfileDeletionPendingException)
            assertTrue(journal.exists())
            assertTrue(stored.observeSettings().first().recommendationProfiles.any { it.profileId == profile.profileId })
            assertTrue(library.codices.observeCodex(removed.codexId).first() == null)
            assertTrue(library.codices.observeCodex(other.codexId).first() != null)
            assertTrue(runCatching { guard.requireCodexWritable(removed.codexId) }.isFailure)
            failSettings = false
            val reopenedGuard = ProfileLibraryWriteGuard()
            val reopened = ProfileDeletionService(settings, library, journal, reopenedGuard,
                AtomicJsonFileStore(ioDispatcher = dispatcher), dispatcher)
            assertTrue(reopened.recoverPendingDeletion())
            assertFalse(reopened.recoverPendingDeletion())
            assertFalse(journal.exists())
            assertTrue(stored.observeSettings().first().recommendationProfiles.none { it.profileId == profile.profileId })
            assertTrue(runCatching { reopenedGuard.requireProfileWritable(profile.profileId) }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `capacity preflight and invalid durable intent fail before library mutation`() = runTest {
        val directory = Files.createTempDirectory("profile-deletion").toFile()
        try {
            val settings = InMemorySettingsRepository()
            val library = InMemoryCodexLikesTransactions()
            val keep = library.codices.ensureCodex(ProfileLibraryIds.codex("profile-main", "keep"), "Keep")
            val journal = directory.resolve("intent.json")
            val owner = ProfileDeletionService(settings, library, journal, ProfileLibraryWriteGuard())
            assertTrue(runCatching { owner.removeProfile("missing") }.isFailure)
            assertFalse(journal.exists())
            journal.writeText("{broken")
            assertTrue(runCatching { owner.recoverPendingDeletion() }.isFailure)
            assertTrue(journal.exists())
            assertTrue(library.codices.observeCodex(keep.codexId).first() != null)
        } finally { directory.deleteRecursively() }
    }
}
