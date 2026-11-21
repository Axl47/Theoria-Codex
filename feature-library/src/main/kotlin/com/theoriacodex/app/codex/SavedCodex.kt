package com.theoriacodex.app.codex

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey

fun savedCodexChildren(likesCodexId: String) = listOf(
    Codex(likesCodexId, "Likes", 0),
    Codex(FOLLOWED_CODEX_ID, "Followed", 0),
    Codex(SAVED_SEARCHES_CODEX_ID, "Searches", 0),
)

/**
 * Posts waiting unseen in Saved's live collections, keyed by collection id, with nothing listed when
 * nothing is new. Library's dots and Saved's shelf order and counts all read it.
 */
fun savedNewCounts(follows: List<FollowedCreator>, searches: Collection<NewPostMarks> = emptyList()): Map<String, Int> =
    mapOf(FOLLOWED_CODEX_ID to follows.sumOf { it.newPostCount }, SAVED_SEARCHES_CODEX_ID to searches.sumOf { it.newPostCount })
        .filterValues { it > 0 }

/** Saved's shelves with the ones holding new posts first, otherwise in their usual order. */
fun List<Codex>.newFirst(newCounts: Map<String, Int>): List<Codex> = sortedByDescending { it.codexId in newCounts }

/** The overview reads the same saved filters as the full collection. */
fun FeedFabRestoreState.savedCollectionFilters() = CodexCollectionFilters(
    animatedOnly = animatedOnly,
    animatedDurationRange = AnimatedDurationRange(durationMinBucket, durationMaxBucket),
    source = source?.let(SourceKey::parse),
    language = CodexLanguageFilter.entries.firstOrNull { it.name == language } ?: CodexLanguageFilter.ANY,
    fullColorOnly = fullColorOnly,
)

