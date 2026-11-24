package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostTaxonomyTerm

fun mergeSparsePost(existing: Post, incoming: Post): Post {
    require(existing.id == incoming.id) {
        "Cannot merge different Post identities: ${existing.id} and ${incoming.id}"
    }
    val existingFull = existing.full
    val incomingFull = incoming.full
    return incoming.copy(
        preview = mergeImageRef(existing.preview, incoming.preview),
        full = when {
            incomingFull == null -> existingFull
            existingFull == null -> incomingFull
            else -> mergeImageRef(existingFull, incomingFull)
        },
        media = mergeMedia(existing.media, incoming.media),
        pageUrl = incoming.pageUrl.presentOr(existing.pageUrl),
        width = incoming.width ?: existing.width,
        height = incoming.height ?: existing.height,
        canonicalTags = mergeStableValues(existing.canonicalTags, incoming.canonicalTags),
        rawTags = mergeStableValues(existing.rawTags, incoming.rawTags),
        authorName = incoming.authorName.presentOr(existing.authorName),
        createdAtEpochMs = incoming.createdAtEpochMs ?: existing.createdAtEpochMs,
        title = incoming.title.presentOr(existing.title),
        creatorProfile = mergeOptionalCreatorProfile(existing.creatorProfile, incoming.creatorProfile),
        durationMs = incoming.durationMs ?: existing.durationMs,
        mediaCount = incoming.mediaCount ?: existing.mediaCount,
        taxonomy = mergeTaxonomy(existing.taxonomy, incoming.taxonomy),
        creatorProfiles = mergeCreatorProfiles(existing.creatorProfiles, incoming.creatorProfiles),
        contentHash = incoming.contentHash ?: existing.contentHash.takeIf {
            val incomingUrl = incomingFull?.url
            incomingUrl.isNullOrBlank() || incomingUrl == existingFull?.url
        },
    )
}

private fun mergeImageRef(existing: ImageRef, incoming: ImageRef): ImageRef = incoming.copy(
    url = incoming.url.presentOr(existing.url),
    localPath = incoming.localPath.presentOr(existing.localPath),
    mime = incoming.mime.presentOr(existing.mime),
    progressiveUrls = mergeStableValues(existing.progressiveUrls, incoming.progressiveUrls),
    fallbackUrls = mergeStableValues(existing.fallbackUrls, incoming.fallbackUrls),
    videoVariants = incoming.videoVariants.ifEmpty {
        existing.videoVariants.takeIf { incoming.url.isNullOrBlank() || incoming.url == existing.url }.orEmpty()
    },
    isAnimated = incoming.isAnimated || existing.isAnimated,
    thumbnailUrl = incoming.thumbnailUrl.presentOr(existing.thumbnailUrl),
)

private fun mergeMedia(existing: List<ImageRef>, incoming: List<ImageRef>): List<ImageRef> {
    if (incoming.isEmpty()) return existing
    return List(maxOf(existing.size, incoming.size)) { index ->
        when {
            index >= incoming.size -> existing[index]
            index >= existing.size -> incoming[index]
            else -> mergeImageRef(existing[index], incoming[index])
        }
    }
}

private fun mergeTaxonomy(
    existing: List<PostTaxonomyTerm>,
    incoming: List<PostTaxonomyTerm>,
): List<PostTaxonomyTerm> = mergeStableBy(
    existing,
    incoming,
    key = { term -> Triple(term.value, term.facet, term.sourceNamespace) },
    merge = ::mergeTaxonomyTerm,
)

private fun mergeCreatorProfiles(
    existing: List<CreatorProfile>,
    incoming: List<CreatorProfile>,
): List<CreatorProfile> = mergeStableBy(
    existing,
    incoming,
    key = { profile -> Triple(profile.source, profile.profileId, profile.displayName) },
    merge = ::mergeCreatorProfile,
)

private fun mergeTaxonomyTerm(existing: PostTaxonomyTerm, incoming: PostTaxonomyTerm): PostTaxonomyTerm =
    incoming.copy(
        count = incoming.count ?: existing.count,
        translation = incoming.translation.presentOr(existing.translation),
    )

private fun mergeOptionalCreatorProfile(
    existing: CreatorProfile?,
    incoming: CreatorProfile?,
): CreatorProfile? = when {
    existing == null || incoming == null -> incoming ?: existing
    else -> mergeCreatorProfile(existing, incoming)
}

private fun mergeCreatorProfile(existing: CreatorProfile, incoming: CreatorProfile): CreatorProfile {
    if (existing.source != incoming.source) return incoming
    return incoming.copy(
        displayName = incoming.displayName.ifBlank { existing.displayName },
        profileId = incoming.profileId.presentOr(existing.profileId),
        profileUrl = incoming.profileUrl.presentOr(existing.profileUrl),
        uploadsQuery = incoming.uploadsQuery.presentOr(existing.uploadsQuery),
    )
}

private fun <T : Any> mergeStableValues(existing: List<T>, incoming: List<T>): List<T> =
    mergeStableBy(existing, incoming, key = { it })

private inline fun <T : Any, K> mergeStableBy(
    existing: List<T>,
    incoming: List<T>,
    key: (T) -> K,
    merge: (existing: T, incoming: T) -> T = { _, entry -> entry },
): List<T> {
    if (incoming.isEmpty()) return existing
    val incomingKeys = incoming.mapTo(mutableSetOf(), key)
    val storedByKey = buildMap<K, T> { existing.forEach { entry -> getOrPut(key(entry)) { entry } } }
    return buildList(incoming.size + existing.size) {
        incoming.mapTo(this) { entry -> storedByKey[key(entry)]?.let { stored -> merge(stored, entry) } ?: entry }
        existing.filterTo(this) { key(it) !in incomingKeys }
    }
}

private fun String?.presentOr(existing: String?): String? =
    this?.takeUnless(String::isBlank) ?: existing
