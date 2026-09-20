package com.theoriacodex.app.codex

import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import java.util.UUID
import kotlinx.coroutines.flow.first

/** Keeps the system Likes Codex and the profile's LikesRepository membership in one transaction flow. */
class LikesCodexSyncService internal constructor(
    private val transactions: CodexLikesTransactions,
    private val codexRepository: CodexRepository,
) {
    suspend fun toggle(
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
            post = post,
            tags = trainingTags,
            eligibleAutomaticCodexIds = automaticCodexIds,
        ).nowLiked
        return if (nowLiked) LikeToggleOutcome.LIKED else LikeToggleOutcome.UNLIKED
    }

    suspend fun clearProfile(profileId: String) {
        transactions.clearLikesAndLikedMemberships(
            profileId = profileId,
            systemCodexId = likesCodexIdForProfile(profileId),
        )
    }

    suspend fun removeProfileCodex(profileId: String) {
        transactions.clearLikesAndDeleteSystemCodex(
            profileId = profileId,
            systemCodexId = likesCodexIdForProfile(profileId),
        )
    }

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

internal fun likesCodexIdForProfile(profileId: String): String {
    return com.theoriacodex.data.repository.ProfileLibraryIds.likes(profileId)
}

internal fun profileScopedCodexId(
    profileId: String,
    uniqueId: String = UUID.randomUUID().toString(),
): String = com.theoriacodex.data.repository.ProfileLibraryIds.codex(profileId, uniqueId)

internal fun codexBelongsToProfile(codexId: String, profileId: String): Boolean {
    return com.theoriacodex.data.repository.ProfileLibraryIds.belongsTo(codexId, profileId)
}

internal const val PROFILE_CODEX_ID_PREFIX = com.theoriacodex.data.repository.ProfileLibraryIds.CODEX_PREFIX
internal const val LIKES_CODEX_ID_PREFIX = com.theoriacodex.data.repository.ProfileLibraryIds.LIKES_PREFIX
private const val LIKES_CODEX_NAME = "Likes"
private const val DEFAULT_PROFILE_ID = "profile-main"
private const val DEFAULT_PROFILE_NAME = "Main"
