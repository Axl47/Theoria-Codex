package com.theoriacodex.app.search

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/** Platform-free visibility context carried from Search rendering into Viewer navigation. */
data class SearchVisibilityFilters(
    val animatedOnly: Boolean = false,
    val hideLiked: Boolean = false,
    val hideSaved: Boolean = false,
    val hideWatched: Boolean = false,
    val animatedDurationRange: AnimatedDurationRange = AnimatedDurationRange.Full,
)

enum class UnknownAnimatedDurationPolicy {
    HIDE_UNKNOWNS,
    RESOLVE_IN_BACKGROUND,
}

data class FavoriteTagSection(
    val source: SourceKey,
    val tags: List<String>,
)

fun filterSearchResults(
    results: List<Post>,
    filters: SearchVisibilityFilters,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId> = emptySet(),
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
): List<Post> {
    return results.filter { post ->
        (!filters.animatedOnly || isAnimatedPost(post)) &&
            matchesAnimatedDurationFilter(post, filters, unknownAnimatedDurationPolicy) &&
            (!filters.hideLiked || post.id !in likedPostIds) &&
            (!filters.hideSaved || post.id !in savedPostIds) &&
            (!filters.hideWatched || post.id !in watchedPostIds)
    }
}

private fun matchesAnimatedDurationFilter(
    post: Post,
    filters: SearchVisibilityFilters,
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy,
): Boolean {
    val range = filters.animatedDurationRange
    if (range.isFullRange) return true
    if (!isAnimatedPost(post)) return true
    val durationMs = animatedDurationMs(post) ?: return when (unknownAnimatedDurationPolicy) {
        UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
        UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND,
        -> false
    }
    return range.contains(durationMs)
}

fun favoriteTagSections(
    mode: QueryMode,
    favoriteTags: Map<SourceKey, List<String>>,
    sourceDisplayOrder: List<SourceKey>,
): List<FavoriteTagSection> {
    val orderedSources = (sourceDisplayOrder + favoriteTags.keys).distinct()
    return when (mode) {
        is QueryMode.Source -> favoriteTags[mode.source]
            .orEmpty()
            .takeIf(List<String>::isNotEmpty)
            ?.let { tags -> listOf(FavoriteTagSection(mode.source, tags)) }
            .orEmpty()

        QueryMode.Unified -> orderedSources.mapNotNull { source ->
            favoriteTags[source]
                .orEmpty()
                .takeIf(List<String>::isNotEmpty)
                ?.let { tags -> FavoriteTagSection(source, tags) }
        }
    }
}
