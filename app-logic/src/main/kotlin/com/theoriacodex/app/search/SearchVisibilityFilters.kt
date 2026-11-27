package com.theoriacodex.app.search

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

data class SearchVisibilityFilters(
    val animatedOnly: Boolean = false,
    val hideLiked: Boolean = false,
    val hideSaved: Boolean = false,
    val hideWatched: Boolean = false,
    val animatedDurationRange: AnimatedDurationRange = AnimatedDurationRange.Full,
    val dateRange: DateRange? = null,
)

data class FavoriteTagSection(
    val source: SourceKey,
    val entries: List<FavoriteTagEntry>,
)

data class PostMarkInputs(
    val liked: Set<PostId>,
    val saved: Set<PostId>,
    val watched: Set<PostId>,
    val retained: Set<PostId>,
)

fun SearchVisibilityFilters.markInputs(
    liked: Set<PostId>,
    saved: Set<PostId>,
    watched: Set<PostId>,
    retained: Set<PostId>,
): PostMarkInputs = PostMarkInputs(
    liked = if (hideLiked) liked else emptySet(),
    saved = if (hideSaved) saved else emptySet(),
    watched = if (hideWatched) watched else emptySet(),
    retained = if (hideLiked || hideSaved || hideWatched) retained else emptySet(),
)

fun filterSearchResults(
    results: List<Post>,
    filters: SearchVisibilityFilters,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId> = emptySet(),
    knownDurationMsByPostId: Map<PostId, Long> = emptyMap(),
    retainedLikedPostIds: Set<PostId> = emptySet(),
    nowEpochMs: Long = System.currentTimeMillis(),
): List<Post> {
    val dates = filters.dateRange?.boundsAt(nowEpochMs)
    return results.filter { post ->
        (!filters.animatedOnly || matchesAnimatedFilter(post)) &&
            (dates == null || post.createdAtEpochMs in dates) &&
            matchesAnimatedDurationFilter(post, filters, knownDurationMsByPostId[post.id]) &&
            (!filters.hideLiked || post.id !in likedPostIds || post.id in retainedLikedPostIds) &&
            (!filters.hideSaved || post.id !in savedPostIds || post.id in retainedLikedPostIds) &&
            (!filters.hideWatched || post.id !in watchedPostIds || post.id in retainedLikedPostIds)
    }
}

val Query.hidesStillsOnPhone: Boolean
    get() = media == MediaFilter.ANIMATED && mode != QueryMode.Source(SourceKey.NHENTAI)

fun List<Post>.keptByMedia(query: Query): List<Post> = if (query.hidesStillsOnPhone) filter(::matchesAnimatedFilter) else this

private val ANIMATED_TAG_MARKERS = listOf("video", "animated", "animation", "animated_gif", "animated_image", "gif")

private fun matchesAnimatedFilter(post: Post): Boolean =
    isAnimatedPost(post) || post.rawTags.any(::isAnimatedTag) || post.canonicalTags.any(::isAnimatedTag)

private fun isAnimatedTag(tag: String): Boolean = ANIMATED_TAG_MARKERS.any { it.equals(tag, ignoreCase = true) }

private fun matchesAnimatedDurationFilter(post: Post, filters: SearchVisibilityFilters, acquiredDurationMs: Long?): Boolean {
    val range = filters.animatedDurationRange
    if (range.isFullRange) return true
    if (!isAnimatedPost(post)) return false
    val durationMs = animatedDurationMs(post) ?: acquiredDurationMs?.takeIf { it > 0L } ?: return false
    return range.contains(durationMs)
}

fun favoriteTagSections(
    mode: QueryMode,
    favoriteTags: List<FavoriteTagEntry>,
    sourceDisplayOrder: List<SourceKey>,
): List<FavoriteTagSection> {
    val bySource = favoriteTags.groupBy(FavoriteTagEntry::source)
    val sources = when (mode) {
        is QueryMode.Source -> listOf(mode.source)
        QueryMode.Unified -> (sourceDisplayOrder + bySource.keys).distinct()
    }
    return sources.mapNotNull { source -> bySource[source]?.let { entries -> FavoriteTagSection(source, entries) } }
}
