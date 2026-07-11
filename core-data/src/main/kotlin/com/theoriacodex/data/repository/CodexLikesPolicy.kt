package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex

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
