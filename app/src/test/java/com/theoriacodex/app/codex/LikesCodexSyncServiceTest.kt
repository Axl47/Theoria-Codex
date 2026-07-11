package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.RecommendationProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LikesCodexSyncServiceTest {
    @Test
    fun `toggle keeps likes and system Codex membership aligned`() = runTest {
        val likes = InMemoryLikesRepository()
        val codices = InMemoryCodexRepository()
        val service = LikesCodexSyncService(likes, codices)
        val profile = RecommendationProfile("profile-main", "Main")
        val post = testPost(sourcePostId = "liked")

        assertTrue(service.toggle(profile, post, listOf("tag")))
        assertEquals(listOf(post.id), likes.observeLikedPostIds(profile.profileId).first().toList())
        assertEquals(
            listOf(post.id),
            codices.observeCodexPosts(likesCodexIdForProfile(profile.profileId), CodexSortMode.NEWEST_SAVED)
                .first()
                .map { it.id },
        )

        assertFalse(service.toggle(profile, post, listOf("tag")))
        assertTrue(likes.observeLikedPostIds(profile.profileId).first().isEmpty())
        assertTrue(
            codices.observeCodexPosts(likesCodexIdForProfile(profile.profileId), CodexSortMode.NEWEST_SAVED)
                .first()
                .isEmpty()
        )
    }

    @Test
    fun `clear and remove profile Codex are idempotent`() = runTest {
        val likes = InMemoryLikesRepository()
        val codices = InMemoryCodexRepository()
        val service = LikesCodexSyncService(likes, codices)
        val profile = RecommendationProfile("profile-alt", "Alt")
        service.toggle(profile, testPost(sourcePostId = "one"), listOf("tag"))
        service.toggle(profile, testPost(sourcePostId = "two"), listOf("tag"))

        service.clearProfile(profile.profileId)
        assertTrue(likes.observeLikes(profile.profileId).first().isEmpty())
        assertTrue(
            codices.observeCodexPosts(likesCodexIdForProfile(profile.profileId), CodexSortMode.NEWEST_SAVED)
                .first()
                .isEmpty()
        )

        service.removeProfileCodex(profile.profileId)
        service.removeProfileCodex(profile.profileId)
        assertNull(codices.observeCodex(likesCodexIdForProfile(profile.profileId)).first())
    }
}
