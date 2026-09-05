package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** In-memory collection mechanics used only by repository test fixtures. */
internal object FixtureRepositoryPolicies {
    data class Result<State, Value>(val state: State, val value: Value)

    fun reorderCodices(
        codices: List<Codex>,
        codexId: String,
        targetIndex: Int,
    ): List<Codex> {
        if (codices.isEmpty()) return codices
        val sourceIndex = codices.indexOfFirst { codex -> codex.codexId == codexId }
        if (sourceIndex < 0) return codices
        val clampedTarget = targetIndex.coerceIn(0, codices.lastIndex)
        if (sourceIndex == clampedTarget) return codices

        return codices.toMutableList().apply {
            add(clampedTarget, removeAt(sourceIndex))
        }
    }

    fun removeCodexItem(items: List<CodexItem>, postId: PostId): List<CodexItem> {
        return items.filterNot { item -> item.postId == postId }
    }

    fun sortCodexPairs(
        pairs: List<Pair<CodexItem, Post>>,
        sort: CodexSortMode,
    ): List<Pair<CodexItem, Post>> {
        return when (sort) {
            CodexSortMode.NEWEST_SAVED -> pairs.sortedByDescending { it.first.savedAtEpochMs }
            CodexSortMode.OLDEST_SAVED -> pairs.sortedBy { it.first.savedAtEpochMs }
            CodexSortMode.BY_SOURCE -> pairs.sortedWith(
                compareBy<Pair<CodexItem, Post>> { it.second.id.source.name }
                    .thenByDescending { it.first.savedAtEpochMs }
                    .thenBy { it.second.id.sourcePostId }
            )
        }
    }

    fun normalizeRecentWatched(
        entries: List<RecentPostEntry>,
        limit: Int,
    ): List<RecentPostEntry> {
        return entries
            .sortedByDescending { entry -> entry.viewedAtEpochMs }
            .distinctBy { entry -> entry.post.id to entry.section }
            .take(limit.coerceAtLeast(0))
    }

    fun normalizeRecentSearches(
        entries: List<RecentSearchEntry>,
        limit: Int,
    ): List<RecentSearchEntry> {
        return entries
            .asSequence()
            .mapNotNull { entry ->
                entry.queryHash.trim().takeIf(String::isNotBlank)?.let { hash ->
                    entry.copy(queryHash = hash)
                }
            }
            .sortedByDescending { entry -> entry.searchedAtEpochMs }
            .distinctBy { entry -> entry.queryHash }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun recordSearch(
        entries: List<RecentSearchEntry>,
        entry: RecentSearchEntry,
        limit: Int,
    ): List<RecentSearchEntry> {
        return normalizeRecentSearches(listOf(entry) + entries, limit)
    }

    fun mergeRecentActivity(
        watched: List<RecentPostEntry>,
        searches: List<RecentSearchEntry>,
    ): List<RecentActivityEntry> {
        return buildList {
            watched
                .filterNot { entry -> entry.section == RecentPostSection.FYP }
                .sortedByDescending { entry -> entry.viewedAtEpochMs }
                .distinctBy { entry -> entry.post.id }
                .forEach { entry -> add(RecentActivityEntry.Watched(entry)) }
            searches.forEach { entry -> add(RecentActivityEntry.Search(entry)) }
        }.sortedByDescending { entry -> entry.occurredAtEpochMs }
    }

    fun toggleLike(
        likesByProfile: Map<String, Map<PostId, LikedPost>>,
        profileId: String,
        postId: PostId,
        tags: List<String>,
        likedAtEpochMs: Long,
    ): Result<Map<String, Map<PostId, LikedPost>>, Boolean> {
        val normalizedProfileId = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalizedProfileId.isBlank()) return Result(likesByProfile, false)
        val profileLikes = likesByProfile[normalizedProfileId].orEmpty().toMutableMap()
        val nowLiked = if (postId in profileLikes) {
            profileLikes -= postId
            false
        } else {
            profileLikes[postId] = LikedPost(
                profileId = normalizedProfileId,
                postId = postId,
                likedAtEpochMs = likedAtEpochMs,
                tags = CodexLikesPolicy.normalizeLikedTags(tags),
            )
            true
        }
        val updated = likesByProfile.toMutableMap().apply {
            if (profileLikes.isEmpty()) remove(normalizedProfileId) else put(normalizedProfileId, profileLikes)
        }
        return Result(updated, nowLiked)
    }

}
