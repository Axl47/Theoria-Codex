package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FollowedCreatorRecord(
    @SerialName("source") val source: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("profileId") val profileId: String? = null,
    @SerialName("profileUrl") val profileUrl: String? = null,
    @SerialName("uploadsQuery") val uploadsQuery: String? = null,
    @SerialName("membershipId") val membershipId: String? = null,
    @SerialName("seenPostIds") val seenPostIds: List<String>? = null,
    @SerialName("latestPostIds") val latestPostIds: List<String>? = null,
    @SerialName("checkedAtEpochMs") val checkedAtEpochMs: Long? = null,
    @SerialName("attemptedAtEpochMs") val attemptedAtEpochMs: Long? = null,
) {
    fun toDomain(): FollowedCreator? {
        val sourceKey = source?.let(SourceKey::parse) ?: return null
        val query = uploadsQuery?.takeIf(String::isNotBlank) ?: return null
        val membership = membershipId?.takeIf(String::isNotBlank) ?: return null
        return FollowedCreator(CreatorProfile(sourceKey, name.orEmpty(), profileId, profileUrl, query),
            membership, seenPostIds.orEmpty().distinct().take(200), latestPostIds.orEmpty().distinct().take(200), checkedAtEpochMs, attemptedAtEpochMs)
    }

    companion object {
        fun fromDomain(follow: FollowedCreator) = with(follow) {
            FollowedCreatorRecord(creator.source.id, creator.displayName, creator.profileId,
                creator.profileUrl, creator.uploadsQuery, membershipId, seenPostIds, latestPostIds, checkedAtEpochMs, attemptedAtEpochMs)
        }
    }
}
