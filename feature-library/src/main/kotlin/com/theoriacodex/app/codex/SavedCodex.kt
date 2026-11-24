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

fun savedNewPosts(follows: List<FollowedCreator>, searches: Collection<NewPostMarks> = emptyList()): Map<String, Set<String>> =
    mapOf(
        FOLLOWED_CODEX_ID to follows.flatMapTo(mutableSetOf()) { follow -> follow.newPostIds.map { it.markKey() } },
        SAVED_SEARCHES_CODEX_ID to searches.flatMapTo(mutableSetOf()) { it.newPostIds },
    ).filterValues { it.isNotEmpty() }

fun List<Codex>.newFirst(news: Map<String, Set<String>>): List<Codex> = sortedByDescending { it.codexId in news }

fun FeedFabRestoreState.savedCollectionFilters() = CodexCollectionFilters(
    animatedOnly = animatedOnly,
    animatedDurationRange = AnimatedDurationRange(durationMinBucket, durationMaxBucket),
    source = source?.let(SourceKey::parse),
    language = CodexLanguageFilter.entries.firstOrNull { it.name == language } ?: CodexLanguageFilter.ANY,
    fullColorOnly = fullColorOnly,
)

