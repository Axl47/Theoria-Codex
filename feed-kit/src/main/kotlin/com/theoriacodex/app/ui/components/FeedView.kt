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
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

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

fun interface FeedPostFilter {
    fun filter(posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>): List<Post>

    fun withDateRange(range: DateRange?): FeedPostFilter = this
}

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

    override fun withDateRange(range: DateRange?): FeedPostFilter = copy(filters = filters.copy(dateRange = range))
}

class FeedView(
    val filters: SearchVisibilityFilters,
    val durations: DurationFilterMetadata,
    val durationFilterActive: Boolean,
    val visiblePosts: List<Post>,
    val related: RelatedFeedPresentation,
    val postFilter: FeedPostFilter,
)

@Composable
fun rememberFeedView(
    retainKey: String,
    posts: List<Post>,
    filters: SearchVisibilityFilters,
    durations: FeedDurations,
    relatedState: RelatedPostsUiState,
    marks: FeedMarks = FeedMarks.NONE,
    durationFilterActive: Boolean = !filters.animatedDurationRange.isFullRange,
    revealInOrder: Boolean = true,
    postFilter: FeedPostFilter? = null,
): FeedView {
    val metadata = rememberDurationFilterMetadata(posts, durations.states, durationFilterActive, retainKey)
    val presented = metadata.presentedPosts?.takeIf { revealInOrder } ?: posts
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
