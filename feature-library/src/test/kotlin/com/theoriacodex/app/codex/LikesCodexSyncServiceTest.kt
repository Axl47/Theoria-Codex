package com.theoriacodex.app.codex

import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.app.post.HASH
import com.theoriacodex.app.post.OTHER
import com.theoriacodex.app.post.filePost
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LikesCodexSyncServiceTest {
    @Test
    fun `toggle keeps likes and system Codex membership aligned`() = runTest {
        val transactions = InMemoryCodexLikesTransactions()
        val likes = transactions.likes
        val codices = transactions.codices
        val service = LikesCodexSyncService(transactions, codices, transactions.likes)
        val profile = RecommendationProfile("profile-main", "Main")
        val post = testPost(sourcePostId = "liked")

        assertEquals(LikeToggleOutcome.LIKED, service.toggle(profile, post, listOf("tag")))
        assertEquals(listOf(post.id), likes.observeLikedPostIds(profile.profileId).first().toList())
        assertEquals(
            listOf(post.id),
            codices.observeCodexPosts(likesCodexIdForProfile(profile.profileId), CodexSortMode.NEWEST_SAVED)
                .first()
                .map { it.id },
        )

        assertEquals(LikeToggleOutcome.UNLIKED, service.toggle(profile, post, listOf("tag")))
        assertTrue(likes.observeLikedPostIds(profile.profileId).first().isEmpty())
        assertTrue(
            codices.observeCodexPosts(likesCodexIdForProfile(profile.profileId), CodexSortMode.NEWEST_SAVED)
                .first()
                .isEmpty()
        )
    }

    @Test
    fun `clearing a profile keeps independently saved Likes Codex posts`() = runTest {
        val transactions = InMemoryCodexLikesTransactions()
        val likes = transactions.likes
        val codices = transactions.codices
        val service = LikesCodexSyncService(transactions, codices, transactions.likes)
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
    }

    @Test
    fun `like routes matching post only into current profile automatic Codices`() = runTest {
        val transactions = InMemoryCodexLikesTransactions()
        val codices = transactions.codices
        val service = LikesCodexSyncService(transactions, codices, transactions.likes)
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

        assertEquals(LikeToggleOutcome.LIKED, service.toggle(profile, post, post.canonicalTags))
        assertEquals(listOf(post.id), codices.observeCodexItems(matching.codexId).first().map { it.postId })
        assertTrue(codices.observeCodexItems(otherProfile.codexId).first().isEmpty())
        assertTrue(codices.observeCodexItems(nonmatching.codexId).first().isEmpty())

        assertEquals(LikeToggleOutcome.UNLIKED, service.toggle(profile, post, post.canonicalTags))
        assertEquals(listOf(post.id), codices.observeCodexItems(matching.codexId).first().map { it.postId })
    }

    @Test
    fun `unliking a copy from another site removes every stored like of that file`() = runTest {
        val transactions = InMemoryCodexLikesTransactions()
        val mirrors = FileMirrorIndex()
        val service = LikesCodexSyncService(transactions, transactions.codices, transactions.likes, mirrors)
        val profile = RecommendationProfile("profile-main", "Main")
        val danbooru = filePost(SourceKey.DANBOORU, "1", HASH)
        val gelbooru = filePost(SourceKey.GELBOORU, "9", HASH)
        val mirror = filePost(SourceKey.RULE34XXX, "5", HASH)
        val unrelated = filePost(SourceKey.DANBOORU, "2", OTHER)
        listOf(danbooru, gelbooru, unrelated).forEach { service.toggle(profile, it, emptyList()) }
        mirrors.learn(listOf(danbooru, gelbooru, unrelated, mirror))

        assertEquals(LikeToggleOutcome.UNLIKED, service.toggle(profile, mirror, emptyList()))
        assertEquals(setOf(unrelated.id), transactions.likes.observeLikedPostIds(profile.profileId).first())

        assertEquals(LikeToggleOutcome.LIKED, service.toggle(profile, mirror, emptyList()))
        assertEquals(setOf(unrelated.id, mirror.id), transactions.likes.observeLikedPostIds(profile.profileId).first())
    }

    @Test
    fun `like if absent leaves another site's liked copy alone and its removal takes back only its own Like`() = runTest {
        var now = 0L
        val transactions = InMemoryCodexLikesTransactions(likes = InMemoryLikesRepository(clock = { ++now }))
        val mirrors = FileMirrorIndex()
        val service = LikesCodexSyncService(transactions, transactions.codices, transactions.likes, mirrors)
        val profile = RecommendationProfile("profile-main", "Main")
        val danbooru = filePost(SourceKey.DANBOORU, "1", HASH)
        val mirror = filePost(SourceKey.RULE34XXX, "5", HASH)
        val fresh = filePost(SourceKey.DANBOORU, "2", OTHER)
        service.toggle(profile, danbooru, emptyList())
        mirrors.learn(listOf(danbooru, mirror, fresh))
        val liked = { transactions.likes.observeLikedPostIds(profile.profileId) }

        assertNull(service.likeIfAbsent(profile, mirror, emptyList()))
        assertEquals(setOf(danbooru.id), liked().first())

        val like = requireNotNull(service.likeIfAbsent(profile, fresh, emptyList()))
        service.toggle(profile, fresh, emptyList())
        service.toggle(profile, fresh, emptyList())
        service.removeLike(profile, fresh, like)
        assertEquals(setOf(danbooru.id, fresh.id), liked().first())

        val again = requireNotNull(transactions.likes.observeLikes(profile.profileId).first().find { it.postId == fresh.id })
        service.removeLike(profile, fresh, again)
        assertEquals(setOf(danbooru.id), liked().first())
    }
}
