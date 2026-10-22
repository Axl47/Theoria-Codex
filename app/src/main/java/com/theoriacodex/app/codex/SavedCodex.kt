package com.theoriacodex.app.codex

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey

/** Presentation container only; its children retain their existing storage identities. */
internal const val SAVED_CODEX_ID = "system:saved"

internal fun savedCodexChildren(likesCodexId: String) = listOf(
    Codex(likesCodexId, "Likes", 0),
    Codex(FOLLOWED_CODEX_ID, "Followed", 0),
    Codex(SAVED_SEARCHES_CODEX_ID, "Searches", 0),
)

internal fun savedCodexSummary(likesCodexId: String, counts: Map<String, Int>): String =
    "Likes: ${counts[likesCodexId] ?: 0}, Followed: ${counts[FOLLOWED_CODEX_ID] ?: 0}, Search: ${counts[SAVED_SEARCHES_CODEX_ID] ?: 0}"

internal fun savedCodexLibraryEntries(codices: List<Codex>, likesCodexId: String): List<Codex> {
    val saved = Codex(SAVED_CODEX_ID, "Saved", 0)
    return if (codices.any { it.codexId == likesCodexId }) {
        codices.map { if (it.codexId == likesCodexId) saved else it }
    } else listOf(saved) + codices
}

/** The overview reads the same saved filters as the full collection. */
internal fun FeedFabRestoreState.savedCollectionFilters() = CodexCollectionFilters(
    animatedOnly = animatedOnly,
    animatedDurationRange = AnimatedDurationRange(durationMinBucket, durationMaxBucket),
    source = SourceKey.entries.firstOrNull { it.name == source },
    language = CodexLanguageFilter.entries.firstOrNull { it.name == language } ?: CodexLanguageFilter.ANY,
    fullColorOnly = fullColorOnly,
)

