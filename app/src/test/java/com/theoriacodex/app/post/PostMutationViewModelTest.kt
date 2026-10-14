package com.theoriacodex.app.post

import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostMutationViewModelTest {
    @Test fun `Like retains its captured profile and sends a completion only after commit`() = runTest {
        val settings = InMemorySettingsRepository()
        val first = settings.addRecommendationProfile("First")
        val second = settings.addRecommendationProfile("Second")
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val owner = PostMutationViewModel(settings, { profile, _, _ ->
            release.await()
            committed += profile.profileId
            LikeToggleOutcome.LIKED
        }, this, StandardTestDispatcher(testScheduler))
        val outcomes = mutableListOf<LikeToggleOutcome>()
        owner.like(first, testPost(), outcomes::add)
        runCurrent()
        assertTrue(outcomes.isEmpty())
        settings.setActiveProfile(second.profileId)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(first.profileId), committed)
        assertEquals(listOf(LikeToggleOutcome.LIKED), outcomes)
        assertTrue(owner.feedback.notices.value.isEmpty())
    }

    @Test fun `failed Like and favorite mutations retain feedback until acknowledged`() = runTest {
        val stored = InMemorySettingsRepository()
        val settings = object : SettingsRepository by stored {
            override suspend fun addFavoriteTag(profileId: String, source: SourceKey, tag: String): Boolean =
                error("disk unavailable")
        }
        val owner = PostMutationViewModel(settings, { _, _, _ -> error("Room unavailable") },
            this, StandardTestDispatcher(testScheduler))
        owner.like(stored.observeSettings().first().recommendationProfiles.first(), testPost()) { error("No commit occurred") }
        owner.favorite("profile-main", SourceKey.PIXIV, "tag", true)
        advanceUntilIdle()
        val notices = owner.feedback.notices.value
        assertEquals(2, notices.size)
        notices.forEach { owner.feedback.acknowledge(it.id) }
        assertTrue(owner.feedback.notices.value.isEmpty())
    }
}
