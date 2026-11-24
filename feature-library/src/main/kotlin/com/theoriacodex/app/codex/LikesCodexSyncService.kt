package com.theoriacodex.app.codex

import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.data.repository.ClearedLikesSnapshot
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the system Likes Codex and the profile's LikesRepository membership in one transaction flow. */
class LikesCodexSyncService constructor(
    private val transactions: CodexLikesTransactions,
    private val codexRepository: CodexRepository,
    private val likes: LikesRepository,
    private val mirrors: FileMirrorIndex = FileMirrorIndex(),
    private val canonicalizePost: (Post) -> Post = { it },
) {
    /** Serializes this service's like writes, so each one's check and change see no other write in between. */
    private val writes = Mutex()

    /**
     * Likes [post], or, when its file is already liked under any site's ID (see [FileMirrorIndex]), removes every one
     * of those likes, so a heart filled by another site's copy empties.
     */
    suspend fun toggle(
        profile: RecommendationProfile,
        post: Post,
        trainingTags: List<String>,
    ): LikeToggleOutcome = writes.withLock {
        val likedCopies = likedCopies(profile, post)
        if (likedCopies.isEmpty()) return@withLock toggleOne(profile, post, trainingTags)
        likedCopies.forEach { id ->
            // Removal reads only the ID; the stored copy is passed so a concurrent change can never like the wrong post.
            val copy = if (id == post.id) post else codexRepository.getPost(id) ?: return@forEach
            toggleOne(profile, copy, trainingTags)
        }
        LikeToggleOutcome.UNLIKED
    }

    /**
     * Likes [post] unless it, or another site's copy of its file, is already liked. Returns the Like it added, which
     * [removeLike] takes back, or null when it added none.
     */
    suspend fun likeIfAbsent(profile: RecommendationProfile, post: Post, trainingTags: List<String>): LikedPost? = writes.withLock {
        if (likedCopies(profile, post).isNotEmpty()) return@withLock null
        check(toggleOne(profile, post, trainingTags) == LikeToggleOutcome.LIKED) { "The Like was not added" }
        storedLike(profile.profileId, post)
    }

    /** Removes [like], a Like of [post], while it is still stored; a newer Like of the post, or none, is left as it is. */
    suspend fun removeLike(profile: RecommendationProfile, post: Post, like: LikedPost) = writes.withLock {
        if (storedLike(profile.profileId, post)?.likedAtEpochMs == like.likedAtEpochMs) toggleOne(profile, post, like.tags)
    }

    private suspend fun likedCopies(profile: RecommendationProfile, post: Post): Set<PostId> =
        mirrors.copiesOf(post).intersect(likes.observeLikedPostIds(profile.profileId).first())

    private suspend fun storedLike(profileId: String, post: Post): LikedPost? =
        likes.observeLikes(profileId).first().firstOrNull { like -> like.postId == post.id }

    private suspend fun toggleOne(
        profile: RecommendationProfile,
        post: Post,
        trainingTags: List<String>,
    ): LikeToggleOutcome {
        val systemCodexId = likesCodexIdForProfile(profile.profileId)
        val automaticCodexIds = codexRepository.observeCodices().first()
            .asSequence()
            .filter { codex ->
                codex.codexId != systemCodexId &&
                    codexBelongsToProfile(codex.codexId, profile.profileId)
            }
            .mapTo(linkedSetOf()) { codex -> codex.codexId }
        val nowLiked = transactions.toggleLikeAndSyncSystemCodex(
            profileId = profile.profileId,
            systemCodexId = systemCodexId,
            systemCodexName = likesCodexNameForProfile(profile),
            post = canonicalizePost(post),
            tags = trainingTags,
            eligibleAutomaticCodexIds = automaticCodexIds,
        ).nowLiked
        return if (nowLiked) LikeToggleOutcome.LIKED else LikeToggleOutcome.UNLIKED
    }

    /** Returns what Undo needs to bring the Likes back, or null when there were none. */
    suspend fun clearProfile(profileId: String): ClearedLikesSnapshot? =
        transactions.clearLikesAndLikedMemberships(
            profileId = profileId,
            systemCodexId = likesCodexIdForProfile(profileId),
        ).undo

    suspend fun restoreClearedLikes(snapshot: ClearedLikesSnapshot) = transactions.restoreClearedLikes(snapshot)

    suspend fun ensureProfileCodex(profile: RecommendationProfile): String {
        return codexRepository.ensureCodex(
            codexId = likesCodexIdForProfile(profile.profileId),
            name = likesCodexNameForProfile(profile),
        ).codexId
    }
}

private fun likesCodexNameForProfile(profile: RecommendationProfile): String {
    return if (profile.name.equals(DEFAULT_PROFILE_NAME, ignoreCase = true)) {
        LIKES_CODEX_NAME
    } else {
        "$LIKES_CODEX_NAME (${profile.name})"
    }
}

fun likesCodexIdForProfile(profileId: String): String {
    return com.theoriacodex.data.repository.ProfileLibraryIds.likes(profileId)
}

fun profileScopedCodexId(
    profileId: String,
    uniqueId: String = UUID.randomUUID().toString(),
): String = com.theoriacodex.data.repository.ProfileLibraryIds.codex(profileId, uniqueId)

internal const val LIKES_CODEX_ID_PREFIX = com.theoriacodex.data.repository.ProfileLibraryIds.LIKES_PREFIX
private const val LIKES_CODEX_NAME = "Likes"
private const val DEFAULT_PROFILE_NAME = "Main"
