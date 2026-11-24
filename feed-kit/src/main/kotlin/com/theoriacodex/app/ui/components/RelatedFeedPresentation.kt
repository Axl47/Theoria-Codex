package com.theoriacodex.app.ui.components

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
import androidx.compose.runtime.remember
import com.theoriacodex.app.media.MediaDurationStates
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.buildRelatedFeedProjection
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.search.markInputs
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

data class RelatedFeedPresentation(
    val visiblePosts: List<Post>,
    val knownDurationMsByPostId: Map<PostId, Long>,
    val projection: RelatedFeedProjection,
)

@Composable
fun rememberRelatedFeedPresentation(
    retainKey: String,
    canonicalPosts: List<Post>,
    visibleCanonicalPosts: List<Post>,
    relatedState: RelatedPostsUiState,
    visibilityFilters: SearchVisibilityFilters,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId>,
    durationStates: MediaDurationStates,
    durationFilterActive: Boolean,
    pendingLikePostIds: Set<PostId> = emptySet(),
): RelatedFeedPresentation {
    val retainedLikedPostIds = retainPostsUntilViewerCloses(remember(relatedState, pendingLikePostIds) {
        relatedState.retainedLikedPostIds + pendingLikePostIds
    })
    val relatedPosts = relatedState.loadedPosts
    val durationMetadata = rememberDurationFilterMetadata(relatedPosts, durationStates, durationFilterActive, "$retainKey:related")
    val marks = visibilityFilters.markInputs(likedPostIds, savedPostIds, watchedPostIds, retainedLikedPostIds)
    val visiblePosts = remember(
        relatedPosts,
        visibilityFilters,
        marks,
        durationMetadata.knownDurationMsByPostId,
    ) {
        filterSearchResults(
            results = relatedPosts,
            filters = visibilityFilters,
            likedPostIds = marks.liked,
            retainedLikedPostIds = marks.retained,
            savedPostIds = marks.saved,
            watchedPostIds = marks.watched,
            knownDurationMsByPostId = durationMetadata.knownDurationMsByPostId,
        )
    }
    val projection = remember(canonicalPosts, visibleCanonicalPosts, relatedState) {
        buildRelatedFeedProjection(canonicalPosts, visibleCanonicalPosts, relatedState)
    }
    return RelatedFeedPresentation(
        visiblePosts = visiblePosts,
        knownDurationMsByPostId = durationMetadata.knownDurationMsByPostId,
        projection = projection,
    )
}

@Composable
fun rememberFeedPageDemand(
    input: FeedPageDemandInput,
    filters: SearchVisibilityFilters,
    gridState: LazyStaggeredGridState,
    projection: RelatedFeedProjection,
    onLoadNextPage: () -> Unit,
): FeedPageDemandControl = rememberFeedPageDemand(
    input = input,
    filters = filters,
    gridState = gridState,
    presentedItemCount = projection.entries.size,
    canonicalIndexForVisibleItems = projection::greatestVisibleCanonicalIndex,
    onLoadNextPage = onLoadNextPage,
)
