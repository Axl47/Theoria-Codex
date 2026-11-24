package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.withDataStoreScope
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.assertStoredFormat
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
            withDataStoreScope { scope ->
                val guard = ProfileLibraryWriteGuard()
                val settings = DataStoreSettingsRepository(directory, scope, profileWriteGuard = guard)
                settings.awaitReady()
                val profile = settings.addRecommendationProfile("Retired")
                val before = settings.observeSettings().first()
                guard.retire(profile.profileId)
                assertTrue(runCatching { settings.addFavoriteTag(profile.profileId,
                    com.theoriacodex.domain.model.SourceKey.PIXIV, listOf("tag")) }.isFailure)
                assertTrue(runCatching { settings.setActiveProfile(profile.profileId) }.isFailure)
                org.junit.Assert.assertEquals(before, settings.observeSettings().first())
                assertTrue(settings.addFavoriteTag("profile-main", com.theoriacodex.domain.model.SourceKey.PIXIV, listOf("tag")))
            }
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

    @Test fun `a journal the Gson store wrote resumes the same removal`() = runTest {
        val directory = Files.createTempDirectory("profile-deletion").toFile()
        try {
            val profileId = "p&<a>='b'"
            val settings = InMemorySettingsRepository()
            settings.updateSettings { it.copy(recommendationProfiles = it.recommendationProfiles + RecommendationProfile(profileId, "Removed")) }
            val removed = mutableListOf<String>()
            val library = object : CodexLikesTransactions by InMemoryCodexLikesTransactions() {
                override suspend fun removeProfileLibrary(profileId: String) { removed += profileId }
            }
            val journal = directory.resolve("intent.json")
            journal.writeText(golden("gson-written.json"))
            val owner = ProfileDeletionService(settings, library, journal, ProfileLibraryWriteGuard())

            assertTrue(owner.recoverPendingDeletion())
            org.junit.Assert.assertEquals(listOf(profileId), removed)
            assertTrue(settings.observeSettings().first().recommendationProfiles.none { it.profileId == profileId })
            assertFalse(journal.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `the profile removal journal golden pins every stored key`() {
        assertStoredFormat(golden("gson-written.json") to ProfileDeletionJournal(1, "p&<a>='b'"))
    }

    @Test fun `a journal from a newer or damaged writer is kept and never acted on`() = runTest {
        val directory = Files.createTempDirectory("profile-deletion").toFile()
        try {
            val library = InMemoryCodexLikesTransactions()
            val keep = library.codices.ensureCodex(ProfileLibraryIds.codex("profile-main", "keep"), "Keep")
            val journal = directory.resolve("intent.json")
            val owner = ProfileDeletionService(InMemorySettingsRepository(), library, journal, ProfileLibraryWriteGuard())
            for (text in listOf("""{"version":2,"profileId":"profile-main"}""", """{"profileId":"profile-main"}""",
                    """{"version":1,"profileId":" "}""", """[1]""", "null")) {
                journal.writeText(text)
                assertTrue(text, runCatching { owner.recoverPendingDeletion() }.isFailure)
                org.junit.Assert.assertEquals(text, journal.readText())
            }
            assertTrue(library.codices.observeCodex(keep.codexId).first() != null)
        } finally { directory.deleteRecursively() }
    }

    private fun golden(name: String) =
        checkNotNull(javaClass.getResource("/golden/profile-deletion/$name")) { "missing golden $name" }.readText()
}
