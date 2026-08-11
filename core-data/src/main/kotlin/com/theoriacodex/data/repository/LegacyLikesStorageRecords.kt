package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName

internal data class LikesStoreFile(
    @field:SerializedName("likes")
    val likes: List<LikedPostRecord>? = null,
)

internal data class LikedPostRecord(
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
) {
    companion object {
        fun fromDomain(profileId: String, liked: LikedPost): LikedPostRecord {
            return LikedPostRecord(
                profileId = profileId,
                source = liked.postId.source.name,
                sourcePostId = liked.postId.sourcePostId,
                likedAtEpochMs = liked.likedAtEpochMs,
                tags = liked.tags,
            )
        }
    }
}

internal fun parseStoredProfileId(profileId: String?, legacyProfile: String?): String {
    val normalizedProfileId = profileId?.trim().orEmpty()
    if (normalizedProfileId.isNotBlank()) return currentProfileId(normalizedProfileId)
    return when (legacyProfile?.trim()) {
        "USER_1" -> "profile-main"
        "USER_2" -> "profile-alt"
        else -> "profile-main"
    }
}

private fun currentProfileId(value: String): String = when (value) {
    "USER_1" -> "profile-main"
    "USER_2" -> "profile-alt"
    else -> value
}
