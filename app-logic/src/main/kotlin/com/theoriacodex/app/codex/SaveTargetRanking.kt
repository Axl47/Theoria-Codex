package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Query
import kotlin.math.exp

data class SaveTargets(val ordered: List<Codex>, val suggestions: List<Codex>, val savedIn: Codex?)

fun rankSaveTargets(
    codices: List<Codex>,
    profileId: String,
    containingCodexIds: Set<String>,
    relevance: Map<String, Double> = emptyMap(),
    searchSaves: SearchSaveMemory? = null,
    query: Query? = null,
    lastSavedAt: Map<String, Long> = emptyMap(),
    excludedCodexIds: Set<String> = emptySet(),
    nowEpochMs: Long = System.currentTimeMillis(),
): SaveTargets {
    val likesId = ProfileLibraryIds.likes(profileId)
    val profileCodices = codices.filterNot { it.codexId == likesId }
    val scores = searchSaves?.boost(query, relevance) ?: relevance
    val score = { codex: Codex ->
        val base = scores[codex.codexId] ?: 0.0
        val recent = lastSavedAt[codex.codexId]?.takeIf { base < SEARCH_SAVE_TIER }?.let { savedAt ->
            RECENT_SAVE_WEIGHT * exp(-(nowEpochMs - savedAt).coerceAtLeast(0L).toDouble() / RECENT_SAVE_DECAY_MS)
        } ?: 0.0
        base + recent * (SEARCH_SAVE_TIER - base)
    }
    val byScore = profileCodices.filterNot { it.codexId in excludedCodexIds }.sortedByDescending(score)
    val (containing, open) = byScore.partition { it.codexId in containingCodexIds }
    return SaveTargets(
        ordered = containing + open,
        suggestions = open.sortedWith(
            compareByDescending(score).thenByDescending { lastSavedAt[it.codexId] ?: Long.MIN_VALUE },
        ),
        savedIn = profileCodices.filter { it.codexId in containingCodexIds }.sortedByDescending(score).firstOrNull(),
    )
}

fun latestSaveTimes(itemsByCodex: Map<String, List<CodexItem>>): Map<String, Long> =
    itemsByCodex.mapValues { (_, items) -> items.maxOfOrNull(CodexItem::savedAtEpochMs) ?: 0L }

private const val RECENT_SAVE_WEIGHT = 0.3
private const val RECENT_SAVE_DECAY_MS = 30 * 60_000.0
