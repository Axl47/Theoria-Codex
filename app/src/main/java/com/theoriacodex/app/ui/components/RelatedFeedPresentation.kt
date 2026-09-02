package com.theoriacodex.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.durationFilterMetadata
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.buildRelatedFeedProjection
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

data class RelatedFeedPresentation(
    val visiblePosts: List<Post>,
    val knownDurationMsByPostId: Map<PostId, Long>,
    val projection: RelatedFeedProjection,
)

@Composable
fun rememberRelatedFeedPresentation(
    canonicalPosts: List<Post>,
    visibleCanonicalPosts: List<Post>,
    relatedState: RelatedPostsUiState,
    visibilityFilters: SearchVisibilityFilters,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId>,
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    durationFilterActive: Boolean,
): RelatedFeedPresentation {
    val relatedPosts = relatedState.loadedPosts
    val durationMetadata = remember(relatedPosts, durationStates, durationFilterActive) {
        durationFilterMetadata(relatedPosts, durationStates, durationFilterActive)
    }
    val visiblePosts = remember(
        relatedPosts,
        visibilityFilters,
        likedPostIds,
        savedPostIds,
        watchedPostIds,
        unknownAnimatedDurationPolicy,
        durationMetadata.knownDurationMsByPostId,
    ) {
        filterSearchResults(
            results = relatedPosts,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            savedPostIds = savedPostIds,
            watchedPostIds = watchedPostIds,
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
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
