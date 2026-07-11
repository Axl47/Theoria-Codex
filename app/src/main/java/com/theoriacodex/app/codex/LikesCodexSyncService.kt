package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import java.util.UUID
import kotlinx.coroutines.flow.first

/** Keeps the system Likes Codex and the profile's LikesRepository membership in one transaction flow. */
class LikesCodexSyncService internal constructor(
    private val likesRepository: LikesRepository,
    private val codexRepository: CodexRepository,
) {
    suspend fun toggle(
        profile: RecommendationProfile,
        post: Post,
        trainingTags: List<String>,
    ): Boolean {
        val nowLiked = likesRepository.toggleLike(
            profileId = profile.profileId,
            postId = post.id,
            tags = trainingTags,
        )
        val codexId = ensureProfileCodex(profile)
        if (nowLiked) {
            codexRepository.addItem(codexId, post)
        } else {
            codexRepository.removeItem(
                codexId = codexId,
                sourceKey = post.id.source,
                sourcePostId = post.id.sourcePostId,
            )
        }
        return nowLiked
    }

    suspend fun clearProfile(profileId: String) {
        val likes = likesRepository.observeLikes(profileId).first()
        likesRepository.clearLikes(profileId)
        likes.forEach { liked ->
            codexRepository.removeItem(
                codexId = likesCodexIdForProfile(profileId),
                sourceKey = liked.postId.source,
                sourcePostId = liked.postId.sourcePostId,
            )
        }
    }

    suspend fun removeProfileCodex(profileId: String) {
        val codexId = likesCodexIdForProfile(profileId)
        if (codexRepository.observeCodex(codexId).first() != null) {
            codexRepository.deleteCodex(codexId)
        }
    }

    suspend fun ensureProfileCodex(profile: RecommendationProfile): String {
        val name = if (profile.name.equals(DEFAULT_PROFILE_NAME, ignoreCase = true)) {
            LIKES_CODEX_NAME
        } else {
            "$LIKES_CODEX_NAME (${profile.name})"
        }
        return codexRepository.ensureCodex(
            codexId = likesCodexIdForProfile(profile.profileId),
            name = name,
        ).codexId
    }
}

internal fun likesCodexIdForProfile(profileId: String): String {
    return if (profileId == DEFAULT_PROFILE_ID) {
        LIKES_CODEX_ID_PREFIX
    } else {
        "${LIKES_CODEX_ID_PREFIX}_$profileId"
    }
}

internal fun profileScopedCodexId(
    profileId: String,
    uniqueId: String = UUID.randomUUID().toString(),
): String = "${PROFILE_CODEX_ID_PREFIX}_${profileId}_$uniqueId"

internal fun codexBelongsToProfile(codexId: String, profileId: String): Boolean {
    if (codexId.startsWith(LIKES_CODEX_ID_PREFIX)) {
        return codexId == likesCodexIdForProfile(profileId)
    }
    if (codexId.startsWith("${PROFILE_CODEX_ID_PREFIX}_")) {
        return codexId.startsWith("${PROFILE_CODEX_ID_PREFIX}_${profileId}_")
    }
    return profileId == DEFAULT_PROFILE_ID
}

internal const val PROFILE_CODEX_ID_PREFIX = "profile_codex"
internal const val LIKES_CODEX_ID_PREFIX = "system_likes_codex"
private const val LIKES_CODEX_NAME = "Likes"
private const val DEFAULT_PROFILE_ID = "profile-main"
private const val DEFAULT_PROFILE_NAME = "Main"
