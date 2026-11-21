package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Query

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
 * Scores are [relevance] lifted by this session's saves from [query] in [searchSaves]; ties keep library order.
 * The profile's Likes and [excludedCodexIds] are never targets. Suggestions break score ties by the most
 * recent save into each Codex ([lastSavedAt], epoch ms by Codex id), so with no scores the most recently used
 * Codex comes first. Empty [relevance] and no [searchSaves] give library order.
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
): SaveTargets {
    val likesId = ProfileLibraryIds.likes(profileId)
    val profileCodices = codices.filterNot { it.codexId == likesId }
    val scores = searchSaves?.boost(query, relevance) ?: relevance
    val score = { codex: Codex -> scores[codex.codexId] ?: 0.0 }
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
