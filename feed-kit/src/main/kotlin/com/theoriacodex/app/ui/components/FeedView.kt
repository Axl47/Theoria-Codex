package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.media.DurationFilterMetadata
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.search.markInputs
import com.theoriacodex.app.search.rememberCarriedFilter
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/**
 * The liked, saved and watched posts a feed's hide filters read, and the likes still being written. Compared by value,
 * so a screen may build one each composition.
 */
data class FeedMarks(
    val liked: Set<PostId> = emptySet(),
    val saved: Set<PostId> = emptySet(),
    val watched: Set<PostId> = emptySet(),
    val pendingLikes: Set<PostId> = emptySet(),
) {
    companion object {
        val NONE = FeedMarks()
    }
}

/**
 * Keeps or drops each of a feed's posts on its own, given the lengths known so far. Feeds refilter when it changes,
 * compared by equality, so implementations are data classes of everything they read.
 */
fun interface FeedPostFilter {
    fun filter(posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>): List<Post>
}

/** Search's filters with the marks they read; the filter every feed uses unless it has its own. */
private data class VisibilityPostFilter(
    val filters: SearchVisibilityFilters,
    val liked: Set<PostId>,
    val saved: Set<PostId>,
    val watched: Set<PostId>,
    val retained: Set<PostId>,
) : FeedPostFilter {
    override fun filter(posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>): List<Post> = filterSearchResults(
        posts, filters, liked, saved, watched,
        knownDurationMsByPostId = knownDurationMsByPostId, retainedLikedPostIds = retained,
    )
}

/**
 * What a feed's grid shows: its posts after the length step and its filters ([visiblePosts]), with any related
 * shelf placed among them ([related]). [filters] is the feed's media and mark filters, which the related shelf and
 * the Viewer apply too; [durations] is the length step over the feed's posts; [postFilter] is the filter step itself,
 * which the triage deck applies to the posts the feed gets later.
 */
class FeedView(
    val filters: SearchVisibilityFilters,
    val durations: DurationFilterMetadata,
    val durationFilterActive: Boolean,
    val visiblePosts: List<Post>,
    val related: RelatedFeedPresentation,
    val postFilter: FeedPostFilter,
)

/**
 * Search, For You, Creator and Codex's one feed pipeline over [posts]: known lengths, then [filters] and [marks]
 * (or a feed's own [postFilter]), then the related shelf from [relatedState]. [retainKey] names the feed, so each
 * step keeps its last result while the Viewer covers it.
 *
 * [durationPosts] is what the length step covers when the feed resolves lengths for more than its grid (For You's
 * home shelves). [revealInOrder] keeps shown cards in place while lengths arrive; a feed sorted by length turns it
 * off, since its order must follow the lengths.
 */
@Composable
fun rememberFeedView(
    retainKey: String,
    posts: List<Post>,
    filters: SearchVisibilityFilters,
    durations: FeedDurations,
    relatedState: RelatedPostsUiState,
    marks: FeedMarks = FeedMarks.NONE,
    durationFilterActive: Boolean = !filters.animatedDurationRange.isFullRange,
    durationPosts: List<Post> = posts,
    revealInOrder: Boolean = true,
    postFilter: FeedPostFilter? = null,
): FeedView {
    val metadata = rememberDurationFilterMetadata(durationPosts, durations.states, durationFilterActive, retainKey)
    val presented = metadata.presentedPosts?.takeIf { revealInOrder && durationPosts === posts } ?: posts
    val retained = retainPostsUntilViewerCloses(remember(relatedState, marks.pendingLikes) {
        relatedState.retainedLikedPostIds + marks.pendingLikes
    })
    val markInputs = filters.markInputs(marks.liked, marks.saved, marks.watched, retained)
    val filter = postFilter ?: VisibilityPostFilter(filters, markInputs.liked, markInputs.saved, markInputs.watched, markInputs.retained)
    val visiblePosts = rememberCarriedFilter(presented, metadata, filter, retainKey = retainKey, filter = filter::filter)
    val related = rememberRelatedFeedPresentation(
        retainKey = retainKey,
        canonicalPosts = posts,
        visibleCanonicalPosts = visiblePosts,
        relatedState = relatedState,
        visibilityFilters = filters,
        likedPostIds = marks.liked,
        savedPostIds = marks.saved,
        watchedPostIds = marks.watched,
        durationStates = durations.states,
        durationFilterActive = durationFilterActive,
        pendingLikePostIds = marks.pendingLikes,
    )
    return remember(filters, metadata, durationFilterActive, visiblePosts, related, filter) {
        FeedView(filters, metadata, durationFilterActive, visiblePosts, related, filter)
    }
}
