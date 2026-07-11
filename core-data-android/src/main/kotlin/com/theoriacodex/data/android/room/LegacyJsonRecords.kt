package com.theoriacodex.data.android.room

import com.google.gson.JsonObject
import com.theoriacodex.domain.model.SourceKey

internal data class LegacyCodexStoreFile(
    val codices: List<LegacyCodexRecord?>? = null,
    val items: Map<String, List<LegacyCodexItemRecord?>?>? = null,
    /**
     * Posts deliberately remain raw until the importer validates their durable schema and keys.
     * The validated object is then decoded through the shared PostStorageRecord/PostStorageCodec
     * boundary instead of maintaining a second local Post serialization model here.
     */
    val posts: List<JsonObject?>? = null,
)

internal data class LegacyCodexRecord(
    val codexId: String? = null,
    val name: String? = null,
    val createdAtEpochMs: Long? = null,
)

internal data class LegacyCodexItemRecord(
    val codexId: String? = null,
    val source: String? = null,
    val sourcePostId: String? = null,
    val savedAtEpochMs: Long? = null,
)

internal data class LegacyLikesStoreFile(
    val likes: List<LegacyLikedPostRecord?>? = null,
)

internal data class LegacyLikedPostRecord(
    val profileId: String? = null,
    val profile: String? = null,
    val source: String? = null,
    val sourcePostId: String? = null,
    val likedAtEpochMs: Long? = null,
    val tags: List<String>? = null,
)

internal fun String?.toSourceKeyOrNull(): SourceKey? {
    return this?.trim()?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
}

internal fun parseStoredProfileId(profileId: String?, legacyProfile: String?): String {
    val normalized = profileId?.trim().orEmpty()
    if (normalized.isNotBlank()) {
        return when (normalized) {
            "USER_1" -> "profile-main"
            "USER_2" -> "profile-alt"
            else -> normalized
        }
    }
    return when (legacyProfile?.trim()) {
        "USER_2" -> "profile-alt"
        else -> "profile-main"
    }
}
