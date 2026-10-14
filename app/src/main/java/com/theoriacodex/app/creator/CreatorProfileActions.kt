package com.theoriacodex.app.creator

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity

fun browseableCreatorProfile(
    profile: CreatorProfile?,
    creatorBrowsingSources: Set<SourceKey>,
): CreatorProfile? {
    if (profile == null || profile.source !in creatorBrowsingSources) return null
    if (profile.uploadsQuery.isNullOrBlank()) return null
    if (profile.source == SourceKey.HITOMI && profile.canonicalHitomiArtistIdentity() == null) {
        return null
    }
    return profile
}

fun creatorButtonLabel(post: Post, creatorBrowsingSources: Set<SourceKey>): String? {
    return creatorProfileActions(post, creatorBrowsingSources).firstOrNull()?.label
}

internal data class CreatorProfileAction(
    val label: String,
    val profile: CreatorProfile?,
) {
    val requiresLegacyResolution: Boolean
        get() = profile == null
}

internal fun creatorProfileActions(
    post: Post,
    creatorBrowsingSources: Set<SourceKey>,
): List<CreatorProfileAction> {
    if (post.id.source !in creatorBrowsingSources) return emptyList()

    val explicitProfiles = post.creatorProfiles
        .mapNotNull { profile -> browseableCreatorProfile(profile, creatorBrowsingSources) }
        .distinct()
    if (explicitProfiles.isNotEmpty()) {
        return explicitProfiles.map { profile ->
            CreatorProfileAction(
                label = profile.displayName.trim(),
                profile = profile,
            )
        }.filter { action -> action.label.isNotBlank() }
    }

    if (post.creatorProfiles.isNotEmpty()) return emptyList()
    val fallbackLabel = post.creatorProfile?.displayName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: post.authorName?.trim()?.takeIf(String::isNotBlank)
        ?: return emptyList()
    return listOf(CreatorProfileAction(label = fallbackLabel, profile = null))
}

