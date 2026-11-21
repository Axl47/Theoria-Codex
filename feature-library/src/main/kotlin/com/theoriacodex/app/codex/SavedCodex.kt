package com.theoriacodex.app.codex

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey

fun savedCodexChildren(likesCodexId: String) = listOf(
    Codex(likesCodexId, "Likes", 0),
    Codex(FOLLOWED_CODEX_ID, "Followed", 0),
    Codex(SAVED_SEARCHES_CODEX_ID, "Searches", 0),
)

/** The overview reads the same saved filters as the full collection. */
fun FeedFabRestoreState.savedCollectionFilters() = CodexCollectionFilters(
    animatedOnly = animatedOnly,
    animatedDurationRange = AnimatedDurationRange(durationMinBucket, durationMaxBucket),
    source = source?.let(SourceKey::parse),
    language = CodexLanguageFilter.entries.firstOrNull { it.name == language } ?: CodexLanguageFilter.ANY,
    fullColorOnly = fullColorOnly,
)

