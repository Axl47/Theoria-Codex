package com.theoriacodex.app.codex

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.data.repository.NewPostMarks
import com.theoriacodex.data.repository.markKey
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey

fun savedCodexChildren(likesCodexId: String) = listOf(
    Codex(likesCodexId, "Likes", 0),
    Codex(FOLLOWED_CODEX_ID, "Followed", 0),
    Codex(SAVED_SEARCHES_CODEX_ID, "Searches", 0),
)

/**
 * Posts waiting unseen in Saved's live collections ([markKey]s by collection id), listing only
 * collections with something new. Library's dots and Saved's shelf order, counts and strips read it.
 */
fun savedNewPosts(follows: List<FollowedCreator>, searches: Collection<NewPostMarks> = emptyList()): Map<String, Set<String>> =
    mapOf(
        FOLLOWED_CODEX_ID to follows.flatMapTo(mutableSetOf()) { follow -> follow.newPostIds.map { it.markKey() } },
        SAVED_SEARCHES_CODEX_ID to searches.flatMapTo(mutableSetOf()) { it.newPostIds },
    ).filterValues { it.isNotEmpty() }

/** Saved's shelves with the ones holding new posts first, otherwise in their usual order. */
fun List<Codex>.newFirst(news: Map<String, Set<String>>): List<Codex> = sortedByDescending { it.codexId in news }

/** The overview reads the same saved filters as the full collection. */
fun FeedFabRestoreState.savedCollectionFilters() = CodexCollectionFilters(
    animatedOnly = animatedOnly,
    animatedDurationRange = AnimatedDurationRange(durationMinBucket, durationMaxBucket),
    source = source?.let(SourceKey::parse),
    language = CodexLanguageFilter.entries.firstOrNull { it.name == language } ?: CodexLanguageFilter.ANY,
    fullColorOnly = fullColorOnly,
)

