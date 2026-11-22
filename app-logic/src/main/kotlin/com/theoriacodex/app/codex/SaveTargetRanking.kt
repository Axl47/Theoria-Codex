package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.Codex
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
 * runs into one Codex. Ties keep library order. The profile's Likes and [excludedCodexIds] are never targets.
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
        val recent = lastSavedAt[codex.codexId]?.let { savedAt ->
            RECENT_SAVE_WEIGHT * exp(-(nowEpochMs - savedAt).coerceAtLeast(0L).toDouble() / RECENT_SAVE_DECAY_MS)
        } ?: 0.0
        (scores[codex.codexId] ?: 0.0) + recent
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

/**
 * The boost for a Codex saved into just now, fading by about two thirds every [RECENT_SAVE_DECAY_MS]. Above most tag
 * scores, below a save from the same search ([SearchSaveMemory]).
 */
private const val RECENT_SAVE_WEIGHT = 0.3
private const val RECENT_SAVE_DECAY_MS = 30 * 60_000.0
