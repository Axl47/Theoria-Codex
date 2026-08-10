package com.theoriacodex.app.codex

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
    ): Boolean {
        val systemCodexId = likesCodexIdForProfile(profile.profileId)
        val automaticCodexIds = codexRepository.observeCodices().first()
            .asSequence()
            .filter { codex ->
                codex.codexId != systemCodexId &&
                    codexBelongsToProfile(codex.codexId, profile.profileId)
            }
            .mapTo(linkedSetOf()) { codex -> codex.codexId }
        return transactions.toggleLikeAndSyncSystemCodex(
            profileId = profile.profileId,
            systemCodexId = systemCodexId,
            systemCodexName = likesCodexNameForProfile(profile),
            post = post,
            tags = trainingTags,
            eligibleAutomaticCodexIds = automaticCodexIds,
        ).nowLiked
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
