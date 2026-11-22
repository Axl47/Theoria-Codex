package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Query
import kotlin.math.exp

/**
 * A post's save targets, ranked once so every save surface agrees.
 *
 * [ordered] is the Save-to-Codex picker's list: Codices already holding the post first, then by score.
 * [suggestions] are the best Codices the post is not in yet, for one-tap save surfaces.
 * [inAnyCodex] is true when one of the profile's Codices (Likes aside) already holds the post.
 */
data class SaveTargets(val ordered: List<Codex>, val suggestions: List<Codex>, val inAnyCodex: Boolean)

/**
 * Ranks [codices], one profile's Codices in library order, as save targets in profile [profileId].
 *
 * Scores are [relevance] lifted by this session's saves from [query] in [searchSaves], plus a boost for a Codex saved
 * into in the last half hour or so ([lastSavedAt], epoch ms by Codex id, as of [nowEpochMs]): saves tend to come in
 * runs into one Codex. The boost closes part of the gap to [SEARCH_SAVE_TIER], so it never lifts a Codex over one this
 * search already saved into, whether [searchSaves] or the caller applied that lift. Ties keep library order. The profile's Likes and [excludedCodexIds] are never targets.
 * Suggestions break score ties by the most recent save, so with no scores the most recently used Codex comes first.
 * Empty [relevance], no [searchSaves] and no [lastSavedAt] give library order.
 */
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
        inAnyCodex = profileCodices.any { it.codexId in containingCodexIds },
    )
}

/** When each Codex last received a post, epoch ms by Codex id, for [rankSaveTargets]. */
fun latestSaveTimes(itemsByCodex: Map<String, List<CodexItem>>): Map<String, Long> =
    itemsByCodex.mapValues { (_, items) -> items.maxOfOrNull(CodexItem::savedAtEpochMs) ?: 0L }

/**
 * The share of the gap to [SEARCH_SAVE_TIER] closed for a Codex saved into just now, fading by about two thirds every
 * [RECENT_SAVE_DECAY_MS].
 */
private const val RECENT_SAVE_WEIGHT = 0.3
private const val RECENT_SAVE_DECAY_MS = 30 * 60_000.0
