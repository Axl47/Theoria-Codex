package com.theoriacodex.data.android.room

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.model.SourceKey

internal data class LegacyCodexStoreFile(
    @field:SerializedName("codices")
    val codices: List<LegacyCodexRecord?>? = null,
    @field:SerializedName("items")
    val items: Map<String, List<LegacyCodexItemRecord?>?>? = null,
    /**
     * Posts deliberately remain raw until the importer validates their durable schema and keys.
     * The validated object is then decoded through the shared PostStorageRecord/PostStorageCodec
     * boundary instead of maintaining a second local Post serialization model here.
     */
    @field:SerializedName("posts")
    val posts: List<JsonObject?>? = null,
)

internal data class LegacyCodexRecord(
    @field:SerializedName("codexId")
    val codexId: String? = null,
    @field:SerializedName("name")
    val name: String? = null,
    @field:SerializedName("createdAtEpochMs")
    val createdAtEpochMs: Long? = null,
)

internal data class LegacyCodexItemRecord(
    @field:SerializedName("codexId")
    val codexId: String? = null,
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("sourcePostId")
    val sourcePostId: String? = null,
    @field:SerializedName("savedAtEpochMs")
    val savedAtEpochMs: Long? = null,
)

internal data class LegacyLikesStoreFile(
    @field:SerializedName("likes")
    val likes: List<LegacyLikedPostRecord?>? = null,
)

internal data class LegacyLikedPostRecord(
    @field:SerializedName("profileId")
    val profileId: String? = null,
    @field:SerializedName("profile")
    val profile: String? = null,
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("sourcePostId")
    val sourcePostId: String? = null,
    @field:SerializedName("likedAtEpochMs")
    val likedAtEpochMs: Long? = null,
    @field:SerializedName("tags")
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
