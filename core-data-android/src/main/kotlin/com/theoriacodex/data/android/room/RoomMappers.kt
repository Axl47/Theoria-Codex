package com.theoriacodex.data.android.room

import com.theoriacodex.data.repository.CodexLikesPolicy
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey

// Row <-> domain mappings shared by the Room repositories and the backup store. Lenient readers (`OrNull`) skip a
// row from an unknown source; the backup snapshot reads strictly instead, so a backup never silently drops rows.

internal fun LikedPost.toEntity() = LikedPostEntity(
    profileId, postId.source.id, postId.sourcePostId, likedAtEpochMs, LikedTagsCodec.encode(tags),
)

/** Unreadable tags degrade to none rather than hiding the like. */
internal fun LikedPostEntity.toLikedPostOrNull(): LikedPost? {
    val postId = toPostIdOrNull() ?: return null
    val tags = runCatching { LikedTagsCodec.decode(tagsJson) }.getOrDefault(emptyList())
    return LikedPost(
        profileId = profileId,
        postId = postId,
        likedAtEpochMs = likedAtEpochMs,
        tags = CodexLikesPolicy.normalizeLikedTags(tags),
    )
}

internal fun CodexEntity.toDomain(automaticTagEntities: List<CodexAutomaticTagEntity> = emptyList()): Codex {
    return Codex(
        codexId = codexId,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        automaticTags = CodexLikesPolicy.normalizeAutomaticTags(
            automaticTagEntities.mapNotNull(CodexAutomaticTagEntity::toDomainOrNull),
        ),
    )
}

internal fun CodexAutomaticTagEntity.toDomainOrNull(): CodexAutomaticTag? {
    val sourceKey = SourceKey.parse(source) ?: return null
    return CodexAutomaticTag(source = sourceKey, tag = tagDisplay, groupIndex = groupIndex)
}

internal fun CodexAutomaticTag.toEntity(codexId: String) = CodexAutomaticTagEntity(
    codexId, source.id, sourceTagKey(source, tag), tag, groupIndex,
)

internal fun CodexItemEntity.toDomainOrNull(): CodexItem? {
    val sourceKey = SourceKey.parse(source) ?: return null
    return CodexItem(codexId = codexId, postId = PostId(sourceKey, sourcePostId), savedAtEpochMs = savedAtEpochMs)
}

internal fun CodexItem.toEntity() = CodexItemEntity(codexId, postId.source.id, postId.sourcePostId, savedAtEpochMs)

internal fun RecentSearchEntity.toDomain(): RecentSearchEntry {
    val payload = RecentSearchPayloadCodec.decodeJson(queryPayloadJson)
    return RecentSearchEntry(payload.query, queryHash, searchedAtEpochMs, payload.kind, payload.sources, payload.sourceTags)
}

private fun LikedPostEntity.toPostIdOrNull(): PostId? =
    SourceKey.parse(source)?.let { sourceKey -> PostId(sourceKey, sourcePostId) }
