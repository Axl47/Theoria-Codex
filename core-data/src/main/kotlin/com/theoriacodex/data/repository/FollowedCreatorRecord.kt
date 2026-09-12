package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey

internal data class FollowedCreatorRecord(
    @field:SerializedName("source") val source: String? = null,
    @field:SerializedName("name") val name: String? = null,
    @field:SerializedName("profileId") val profileId: String? = null,
    @field:SerializedName("profileUrl") val profileUrl: String? = null,
    @field:SerializedName("uploadsQuery") val uploadsQuery: String? = null,
    @field:SerializedName("membershipId") val membershipId: String? = null,
    @field:SerializedName("seenPostIds") val seenPostIds: List<String>? = null,
    @field:SerializedName("latestPostIds") val latestPostIds: List<String>? = null,
    @field:SerializedName("checkedAtEpochMs") val checkedAtEpochMs: Long? = null,
) {
    fun toDomain(): FollowedCreator? {
        val sourceKey = SourceKey.entries.firstOrNull { it.name == source } ?: return null
        val query = uploadsQuery?.takeIf(String::isNotBlank) ?: return null
        val membership = membershipId?.takeIf(String::isNotBlank) ?: return null
        return FollowedCreator(CreatorProfile(sourceKey, name.orEmpty(), profileId, profileUrl, query),
            membership, seenPostIds.orEmpty().distinct().take(200), latestPostIds.orEmpty().distinct().take(200), checkedAtEpochMs)
    }

    companion object {
        fun fromDomain(follow: FollowedCreator) = with(follow) {
            FollowedCreatorRecord(creator.source.name, creator.displayName, creator.profileId,
                creator.profileUrl, creator.uploadsQuery, membershipId, seenPostIds, latestPostIds, checkedAtEpochMs)
        }
    }
}
