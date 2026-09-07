package com.theoriacodex.app.settings

import com.theoriacodex.app.codex.LikesCodexSyncService
import com.theoriacodex.app.codex.likesCodexIdForProfile
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProfileWorkflowTest {
    @Test
    fun `create switch and delete removes only the confirmed profile data`() = runTest {
        val settings = InMemorySettingsRepository().apply {
            updateSettings { it.copy(recommendationProfiles = it.recommendationProfiles.take(1)) }
        }
        val transactions = InMemoryCodexLikesTransactions()
        val codices = transactions.codices
        val likes = transactions.likes
        val sync = LikesCodexSyncService(transactions, codices)
        val owner = settingsOwner(settingsRepository = settings, codexRepository = codices,
            likesRepository = likes, profileMutations = DefaultSettingsProfileMutations(sync, codices))
        runCurrent()
        val first = owner.state.value.activeProfile
        owner.onAction(SettingsAction.AddProfile("  Sketching  "))
        runCurrent()
        val second = owner.state.value.settings.recommendationProfiles.single { it.profileId != first.profileId }
        assertEquals("Sketching", second.name)
        val sharedPost = testPost(sourcePostId = "shared")
        sync.toggle(first, sharedPost, listOf("landscape"))
        sync.toggle(second, sharedPost, listOf("landscape"))
        sync.toggle(second, testPost(sourcePostId = "second-only"), listOf("portrait"))
        val firstCollection = codices.ensureCodex(profileScopedCodexId(first.profileId, "saved"), "Main saved")
        val secondCollection = codices.ensureCodex(profileScopedCodexId(second.profileId, "saved"), "Sketch saved")
        codices.addItem(firstCollection.codexId, sharedPost)
        codices.addItem(secondCollection.codexId, sharedPost)
        owner.onAction(SettingsAction.SetActiveProfile(first.profileId))
        runCurrent()
        assertEquals(first, owner.state.value.activeProfile)
        assertEquals(1, owner.state.value.activeProfileLikesCount)
        owner.onAction(SettingsAction.SetActiveProfile(second.profileId))
        runCurrent()
        assertEquals(second, owner.state.value.activeProfile)
        assertEquals(2, owner.state.value.activeProfileLikesCount)

        owner.onAction(SettingsAction.RequestRemoveProfile(second.profileId))
        assertEquals(second.profileId, owner.state.value.profileDeleteTargetId)
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        runCurrent()

        assertEquals(listOf(first), owner.state.value.settings.recommendationProfiles)
        assertEquals(first, owner.state.value.activeProfile)
        assertNull(owner.state.value.profileDeleteTargetId)
        assertTrue(likes.observeLikedPostIds(second.profileId).first().isEmpty())
        assertNull(codices.observeCodex(likesCodexIdForProfile(second.profileId)).first())
        assertNull(codices.observeCodex(secondCollection.codexId).first())
        assertEquals(setOf(sharedPost.id), likes.observeLikedPostIds(first.profileId).first())
        assertEquals(listOf(sharedPost.id), codices.observeCodexItems(firstCollection.codexId).first().map { it.postId })
        assertEquals(listOf(sharedPost.id), codices.observeCodexItems(likesCodexIdForProfile(first.profileId)).first().map { it.postId })
    }

    @Test
    fun `dismissed deletion of a removable profile preserves its data`() = runTest {
        val settings = InMemorySettingsRepository()
        val second = settings.addRecommendationProfile("Sketching")
        val transactions = InMemoryCodexLikesTransactions()
        val sync = LikesCodexSyncService(transactions, transactions.codices)
        sync.toggle(second, testPost(sourcePostId = "keep"), listOf("landscape"))
        val owner = settingsOwner(settingsRepository = settings,
            profileMutations = DefaultSettingsProfileMutations(sync, transactions.codices))
        runCurrent()
        val profiles = owner.state.value.settings.recommendationProfiles
        val saved = transactions.codices.observeCodices().first()
        owner.onAction(SettingsAction.RequestRemoveProfile(second.profileId))
        owner.onAction(SettingsAction.DismissRemoveProfile)
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        runCurrent()
        assertNull(owner.state.value.profileDeleteTargetId)
        assertEquals(profiles, owner.state.value.settings.recommendationProfiles)
        assertEquals(saved, transactions.codices.observeCodices().first())
        assertEquals(1, transactions.likes.observeLikedPostIds(second.profileId).first().size)
    }

    @Test
    fun `last profile and missing profile deletion are rejected before touching data`() = runTest {
        val transactions = InMemoryCodexLikesTransactions()
        val sync = LikesCodexSyncService(transactions, transactions.codices)
        val settings = InMemorySettingsRepository().apply {
            updateSettings { it.copy(recommendationProfiles = it.recommendationProfiles.take(1)) }
        }
        val owner = settingsOwner(settingsRepository = settings,
            profileMutations = DefaultSettingsProfileMutations(sync, transactions.codices))
        runCurrent()
        val original = owner.state.value.activeProfile
        sync.toggle(original, testPost(sourcePostId = "keep"), listOf("landscape"))
        val saved = transactions.codices.observeCodices().first()
        owner.onAction(SettingsAction.RequestRemoveProfile(original.profileId))
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        runCurrent()
        assertEquals(listOf(original), owner.state.value.settings.recommendationProfiles)
        owner.onAction(SettingsAction.AddProfile("Another"))
        runCurrent()
        val profiles = owner.state.value.settings.recommendationProfiles
        assertEquals(2, profiles.size)
        owner.onAction(SettingsAction.RequestRemoveProfile("missing"))
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        owner.onAction(SettingsAction.AddProfile("  "))
        runCurrent()
        assertEquals(profiles, owner.state.value.settings.recommendationProfiles)
        assertEquals(saved, transactions.codices.observeCodices().first())
        assertEquals(1, transactions.likes.observeLikedPostIds(original.profileId).first().size)
    }

    @Test
    fun `failed profile cleanup preserves membership and retry finishes real cleanup`() = runTest {
        val settings = InMemorySettingsRepository()
        val second = settings.addRecommendationProfile("Sketching")
        val transactions = InMemoryCodexLikesTransactions()
        var failDeletion = true
        val codices = object : CodexRepository by transactions.codices {
            override suspend fun deleteCodex(codexId: String) {
                if (failDeletion) error("disk unavailable")
                transactions.codices.deleteCodex(codexId)
            }
        }
        val sync = LikesCodexSyncService(transactions, codices)
        val collection = codices.ensureCodex(profileScopedCodexId(second.profileId, "saved"), "Sketch saved")
        codices.addItem(collection.codexId, testPost(sourcePostId = "keep-until-deleted"))
        val owner = settingsOwner(settingsRepository = settings, codexRepository = codices,
            profileMutations = DefaultSettingsProfileMutations(sync, codices))
        runCurrent()
        owner.onAction(SettingsAction.RequestRemoveProfile(second.profileId))
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        runCurrent()
        assertTrue(owner.state.value.settings.recommendationProfiles.contains(second))
        assertEquals(1, codices.observeCodexItems(collection.codexId).first().size)
        assertEquals(SettingsEffect.ShowMessage("Could not remove profile. Please try again."), owner.effects.first())
        failDeletion = false
        owner.onAction(SettingsAction.RequestRemoveProfile(second.profileId))
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        runCurrent()
        assertFalse(owner.state.value.settings.recommendationProfiles.contains(second))
        assertNull(codices.observeCodex(collection.codexId).first())
    }

    @Test
    fun `cancelled cleanup never removes the profile settings`() = runTest {
        val settings = InMemorySettingsRepository()
        val second = settings.addRecommendationProfile("Sketching")
        val owner = settingsOwner(settingsRepository = settings, profileMutations = object : SettingsProfileMutations {
            override suspend fun removeProfileData(profileId: String) { throw CancellationException("route closed") }
        })
        runCurrent()
        owner.onAction(SettingsAction.RequestRemoveProfile(second.profileId))
        owner.onAction(SettingsAction.ConfirmRemoveProfile)
        runCurrent()
        assertTrue(owner.state.value.settings.recommendationProfiles.contains(second))
    }
}
