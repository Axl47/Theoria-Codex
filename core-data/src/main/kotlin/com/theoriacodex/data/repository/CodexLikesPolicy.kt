package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.tags.sourceTagKey

/** Canonical storage-independent policy shared by JSON, memory, and database repositories. */
object CodexLikesPolicy {
    fun resolveUniqueCodexName(
        requestedName: String,
        existingCodices: List<Codex>,
        excludeCodexId: String? = null,
    ): String {
        val baseName = requestedName.trim().ifBlank { "Codex" }
        val occupiedNames = existingCodices
            .asSequence()
            .filter { codex -> codex.codexId != excludeCodexId }
            .map { codex -> codex.name.trim().lowercase() }
            .toSet()
        if (baseName.lowercase() !in occupiedNames) return baseName

        var suffix = 2
        while (true) {
            val candidate = "$baseName $suffix"
            if (candidate.lowercase() !in occupiedNames) return candidate
            suffix += 1
        }
    }

    fun normalizeProfileId(profileId: String): String = profileId.trim()

    fun normalizeLikedTags(tags: List<String>): List<String> {
        return tags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .toList()
    }

    fun normalizeAutomaticTags(tags: List<CodexAutomaticTag>): List<CodexAutomaticTag> {
        val normalized = tags
            .asSequence()
            .mapNotNull(::normalizeAutomaticTagOrNull)
            .distinctBy { tag -> tag.source to sourceTagKey(tag.source, tag.tag) }
            .toList()
        val compactGroupIndexBySource = normalized.groupBy(CodexAutomaticTag::source)
            .mapValues { (_, sourceTags) ->
                sourceTags.map(CodexAutomaticTag::groupIndex)
                    .distinct()
                    .sorted()
                    .withIndex()
                    .associate { (compactIndex, storedIndex) -> storedIndex to compactIndex }
            }
        return normalized
            .map { tag ->
                tag.copy(groupIndex = compactGroupIndexBySource.getValue(tag.source).getValue(tag.groupIndex))
            }
            .sortedWith(
                compareBy<CodexAutomaticTag> { tag -> tag.source.ordinal }
                    .thenBy(CodexAutomaticTag::groupIndex)
                    .thenBy { tag -> sourceTagKey(tag.source, tag.tag) },
            )
    }

    fun setAutomaticTag(
        current: List<CodexAutomaticTag>,
        requested: CodexAutomaticTag,
        enabled: Boolean,
    ): List<CodexAutomaticTag> {
        val normalizedRequest = normalizeAutomaticTagOrNull(requested)
            ?: return normalizeAutomaticTags(current)
        val requestedKey = sourceTagKey(normalizedRequest.source, normalizedRequest.tag)
        val normalizedCurrent = normalizeAutomaticTags(current)
        val containsRequested = normalizedCurrent.any { tag ->
            tag.source == normalizedRequest.source && sourceTagKey(tag.source, tag.tag) == requestedKey
        }
        if (enabled && containsRequested) return normalizedCurrent
        val withoutRequested = normalizedCurrent.filterNot { tag ->
            tag.source == normalizedRequest.source && sourceTagKey(tag.source, tag.tag) == requestedKey
        }
        return normalizeAutomaticTags(
            if (enabled) withoutRequested + normalizedRequest else withoutRequested,
        )
    }

    fun postMatchesAutomaticTagGroups(post: Post, automaticTags: List<CodexAutomaticTag>): Boolean {
        val postTagKeys = post.canonicalTags
            .asSequence()
            .map { tag -> sourceTagKey(post.id.source, tag) }
            .filter(String::isNotBlank)
            .toSet()
        val sourceGroups = normalizeAutomaticTags(automaticTags)
            .filter { tag -> tag.source == post.id.source }
            .groupBy(CodexAutomaticTag::groupIndex)
        return sourceGroups.isNotEmpty() && sourceGroups.values.all { group ->
            group.any { automaticTag ->
                sourceTagKey(automaticTag.source, automaticTag.tag) in postTagKeys
            }
        }
    }

    /** Compatibility name retained for repository callers; matching is now AND across OR groups. */
    fun postMatchesAnyAutomaticTag(post: Post, automaticTags: List<CodexAutomaticTag>): Boolean =
        postMatchesAutomaticTagGroups(post, automaticTags)

    private fun normalizeAutomaticTagOrNull(tag: CodexAutomaticTag): CodexAutomaticTag? {
        val normalized = tag.copy(
            tag = tag.tag.trim(),
            groupIndex = tag.groupIndex.coerceAtLeast(0),
        )
        return normalized.takeIf { candidate ->
            candidate.tag.isNotBlank() && !candidate.tag.startsWith("-")
        }
    }

    /**
     * Resolves a complete requested order or returns null when it is partial, duplicated, or
     * contains an unknown id. Requiring a complete permutation keeps bulk database reorders from
     * silently moving codices that were not part of the caller's snapshot.
     */
    fun resolveCompleteCodexOrder(
        currentCodices: List<Codex>,
        codexIdsInOrder: List<String>,
    ): List<Codex>? {
        if (codexIdsInOrder.size != currentCodices.size) return null
        if (codexIdsInOrder.distinct().size != codexIdsInOrder.size) return null
        val byId = currentCodices.associateBy(Codex::codexId)
        if (byId.size != currentCodices.size || codexIdsInOrder.any { id -> id !in byId }) return null
        return codexIdsInOrder.map(byId::getValue)
    }
}
