package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
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
        val transactions = InMemoryCodexLikesTransactions()
        val likes = transactions.likes
        val codices = transactions.codices
        val service = LikesCodexSyncService(transactions, codices)
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
        val transactions = InMemoryCodexLikesTransactions()
        val likes = transactions.likes
        val codices = transactions.codices
        val service = LikesCodexSyncService(transactions, codices)
        val profile = RecommendationProfile("profile-alt", "Alt")
        service.toggle(profile, testPost(sourcePostId = "one"), listOf("tag"))
        service.toggle(profile, testPost(sourcePostId = "two"), listOf("tag"))
        val independentlySaved = testPost(sourcePostId = "manual")
        codices.addItem(likesCodexIdForProfile(profile.profileId), independentlySaved)

        service.clearProfile(profile.profileId)
        assertTrue(likes.observeLikes(profile.profileId).first().isEmpty())
        assertEquals(
            listOf(independentlySaved.id),
            codices.observeCodexPosts(
                likesCodexIdForProfile(profile.profileId),
                CodexSortMode.NEWEST_SAVED,
            ).first().map { post -> post.id },
        )

        service.removeProfileCodex(profile.profileId)
        service.removeProfileCodex(profile.profileId)
        assertNull(codices.observeCodex(likesCodexIdForProfile(profile.profileId)).first())
    }

    @Test
    fun `like routes matching post only into current profile automatic Codices`() = runTest {
        val transactions = InMemoryCodexLikesTransactions()
        val codices = transactions.codices
        val service = LikesCodexSyncService(transactions, codices)
        val profile = RecommendationProfile("profile-main", "Main")
        val matching = codices.ensureCodex(
            profileScopedCodexId(profile.profileId, "matching"),
            "Matching",
        )
        val otherProfile = codices.ensureCodex(
            profileScopedCodexId("profile-alt", "other"),
            "Other profile",
        )
        val nonmatching = codices.ensureCodex(
            profileScopedCodexId(profile.profileId, "nonmatching"),
            "Nonmatching",
        )
        codices.setAutomaticTag(
            matching.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "landscape"),
            enabled = true,
        )
        codices.setAutomaticTag(
            otherProfile.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "landscape"),
            enabled = true,
        )
        codices.setAutomaticTag(
            nonmatching.codexId,
            CodexAutomaticTag(SourceKey.PIXIV, "portrait"),
            enabled = true,
        )
        val post = testPost(sourcePostId = "automatic", canonicalTags = listOf("landscape"))

        assertTrue(service.toggle(profile, post, post.canonicalTags))
        assertEquals(listOf(post.id), codices.observeCodexItems(matching.codexId).first().map { it.postId })
        assertTrue(codices.observeCodexItems(otherProfile.codexId).first().isEmpty())
        assertTrue(codices.observeCodexItems(nonmatching.codexId).first().isEmpty())

        assertFalse(service.toggle(profile, post, post.canonicalTags))
        assertEquals(listOf(post.id), codices.observeCodexItems(matching.codexId).first().map { it.postId })
    }
}
