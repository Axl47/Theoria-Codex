package com.theoriacodex.app.search

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.domain.model.DateRange
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
    /** Applied on the device so every source can filter by date, including those with no server support. */
    val dateRange: DateRange? = null,
)

enum class UnknownAnimatedDurationPolicy {
    HIDE_UNKNOWNS,
    RESOLVE_IN_BACKGROUND,
}

fun unknownAnimatedDurationPolicy(resolveInBackground: Boolean): UnknownAnimatedDurationPolicy {
    return if (resolveInBackground) {
        UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
    } else {
        UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
    }
}

/** One source's saved tags and "or" groups, in the order they were saved. */
data class FavoriteTagSection(
    val source: SourceKey,
    val entries: List<FavoriteTagEntry>,
)

/** The liked, saved and watched marks a filter pass actually reads; marks for hide toggles that are off stay empty. */
data class PostMarkInputs(
    val liked: Set<PostId>,
    val saved: Set<PostId>,
    val watched: Set<PostId>,
    val retained: Set<PostId>,
)

/**
 * Narrows the marks to what these filters use. Feeds key their filter pass on the result, so liking,
 * saving or watching a post does not refilter the whole feed while the matching hide toggle is off.
 */
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

/** Keep recommendation seeds reachable until dismissal, including after viewing or automatic saving. */
fun filterSearchResults(
    results: List<Post>,
    filters: SearchVisibilityFilters,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId> = emptySet(),
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
    knownDurationMsByPostId: Map<PostId, Long> = emptyMap(),
    retainedLikedPostIds: Set<PostId> = emptySet(),
): List<Post> {
    return results.filter { post ->
        (!filters.animatedOnly || matchesAnimatedFilter(post)) &&
            (filters.dateRange == null || post.createdAtEpochMs in filters.dateRange) &&
            matchesAnimatedDurationFilter(
                post,
                filters,
                unknownAnimatedDurationPolicy,
                knownDurationMsByPostId[post.id],
            ) &&
            (!filters.hideLiked || post.id !in likedPostIds || post.id in retainedLikedPostIds) &&
            (!filters.hideSaved || post.id !in savedPostIds || post.id in retainedLikedPostIds) &&
            (!filters.hideWatched || post.id !in watchedPostIds || post.id in retainedLikedPostIds)
    }
}

/** The built-in "Animated" saved "or" group for Gelbooru-engine sites, which tag animation several ways. */
val GELBOORU_ANIMATED_SEARCH_TAGS = listOf("video", "animated", "animation", "animated_gif", "animated_image", "gif")

/** Danbooru-engine sites tag animation consistently; a short group keeps within their tag limits. */
val DANBOORU_ANIMATED_SEARCH_TAGS = listOf("animated", "video")

private val animatedTags = GELBOORU_ANIMATED_SEARCH_TAGS.toSet()

/** One rule for every source: animated media, or a tag that says so (covers sites whose previews are stills). */
private fun matchesAnimatedFilter(post: Post): Boolean =
    isAnimatedPost(post) || (post.rawTags + post.canonicalTags).any { it.lowercase() in animatedTags }

private fun matchesAnimatedDurationFilter(
    post: Post,
    filters: SearchVisibilityFilters,
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy,
    acquiredDurationMs: Long?,
): Boolean {
    val range = filters.animatedDurationRange
    if (range.isFullRange) return true
    if (!isAnimatedPost(post)) return false
    val durationMs = animatedDurationMs(post) ?: acquiredDurationMs?.takeIf { it > 0L }
        ?: return when (unknownAnimatedDurationPolicy) {
        UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
        UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND,
        -> false
    }
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
