package com.theoriacodex.data.android.room

import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostTaxonomyTerm

/**
 * Transaction-local owner for Recents writes into the shared posts table.
 *
 * Recents may receive a route snapshot before lazy media resolution finishes. Those absent fields
 * must not erase richer data already owned by Codex or Likes, while newly known values should still
 * refresh the shared payload.
 */
internal class SharedPostPayloadWriter(
    private val dao: CodexLikesDao,
    private val codec: LocalPostPayloadCodec,
) {
    fun upsert(post: Post) {
        val source = post.id.source.name
        val sourcePostId = post.id.sourcePostId
        val existingEntity = dao.post(source, sourcePostId)
        if (existingEntity == null) {
            val inserted = dao.insertPost(PostEntity(source, sourcePostId, codec.encode(post)))
            if (inserted != -1L) return
        }

        val current = requireNotNull(dao.post(source, sourcePostId)) {
            "Shared Post disappeared while merging $source:$sourcePostId"
        }
        val merged = mergeSharedPostPayload(codec.decode(current), post)
        val payload = codec.encode(merged)
        if (payload != current.payloadJson) {
            check(dao.updatePost(source, sourcePostId, payload) == 1) {
                "Shared Post disappeared while updating $source:$sourcePostId"
            }
        }
    }
}

internal fun mergeSharedPostPayload(existing: Post, incoming: Post): Post {
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
        creatorProfile = mergeCreatorProfile(existing.creatorProfile, incoming.creatorProfile),
        durationMs = incoming.durationMs ?: existing.durationMs,
        mediaCount = incoming.mediaCount ?: existing.mediaCount,
        taxonomy = mergeTaxonomy(existing.taxonomy, incoming.taxonomy),
        creatorProfiles = mergeCreatorProfiles(existing.creatorProfiles, incoming.creatorProfiles),
    )
}

private fun mergeImageRef(existing: ImageRef, incoming: ImageRef): ImageRef = incoming.copy(
    url = incoming.url.presentOr(existing.url),
    localPath = incoming.localPath.presentOr(existing.localPath),
    mime = incoming.mime.presentOr(existing.mime),
    progressiveUrls = mergeStableValues(existing.progressiveUrls, incoming.progressiveUrls),
    isAnimated = incoming.isAnimated || existing.isAnimated,
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
): List<PostTaxonomyTerm> = mergeStableBy(existing, incoming) { term ->
    Triple(term.value, term.facet, term.sourceNamespace)
}

private fun mergeCreatorProfiles(
    existing: List<CreatorProfile>,
    incoming: List<CreatorProfile>,
): List<CreatorProfile> = mergeStableBy(existing, incoming) { profile ->
    Triple(profile.source, profile.profileId, profile.displayName)
}

private fun mergeCreatorProfile(
    existing: CreatorProfile?,
    incoming: CreatorProfile?,
): CreatorProfile? {
    if (incoming == null) return existing
    if (existing == null || existing.source != incoming.source) return incoming
    return incoming.copy(
        displayName = incoming.displayName.ifBlank { existing.displayName },
        profileId = incoming.profileId.presentOr(existing.profileId),
        profileUrl = incoming.profileUrl.presentOr(existing.profileUrl),
        uploadsQuery = incoming.uploadsQuery.presentOr(existing.uploadsQuery),
    )
}

private fun <T> mergeStableValues(existing: List<T>, incoming: List<T>): List<T> =
    mergeStableBy(existing, incoming) { it }

private inline fun <T, K> mergeStableBy(
    existing: List<T>,
    incoming: List<T>,
    key: (T) -> K,
): List<T> {
    if (incoming.isEmpty()) return existing
    val incomingKeys = incoming.mapTo(mutableSetOf(), key)
    return buildList(incoming.size + existing.size) {
        addAll(incoming)
        existing.filterTo(this) { key(it) !in incomingKeys }
    }
}

private fun String?.presentOr(existing: String?): String? =
    this?.takeUnless(String::isBlank) ?: existing
