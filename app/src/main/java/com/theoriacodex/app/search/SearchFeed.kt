package com.theoriacodex.app.search

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.DurationFilterMetadata
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.rememberDurationFilterMetadata
import com.theoriacodex.app.related.retainedLikedPostIds
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.app.ui.components.rememberRelatedFeedPresentation
import com.theoriacodex.app.viewer.retainPostsUntilViewerCloses
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/** What the results grid shows after visibility filters, known durations, and related posts apply. */
internal class SearchFeed(
    val animatedFilterActive: Boolean,
    val animatedDurationRange: AnimatedDurationRange,
    val animatedDurationFilterActive: Boolean,
    val visibilityFilters: SearchVisibilityFilters,
    val isNhentaiSourceMode: Boolean,
    val displayResults: List<Post>,
    val durationMetadata: DurationFilterMetadata,
    val visibleResults: List<Post>,
    val related: RelatedFeedPresentation,
)

@Composable
internal fun rememberSearchFeed(
    state: SearchUiState,
    fabRestoreState: FeedFabRestoreState,
    likedPostIds: Set<PostId>,
    pendingLikePostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    watchedPostIds: Set<PostId>,
    durationStates: Map<MediaDurationKey, MediaDurationState>,
    resolveUnknownAnimatedDurations: Boolean,
): SearchFeed {
    val animationRestoreState = fabRestoreState.searchAnimationFilter(state.query.draft.mode)
    val durationMinBucket = animationRestoreState.durationMinBucket
    val durationMaxBucket = animationRestoreState.durationMaxBucket
    val isNhentaiSourceMode = state.query.draft.mode == QueryMode.Source(SourceKey.NHENTAI)
    val animatedFilterActive = animationRestoreState.animatedOnly && !isNhentaiSourceMode
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(minBucket = durationMinBucket, maxBucket = durationMaxBucket)
    }
    val animatedDurationFilterActive = !animatedDurationRange.isFullRange && !isNhentaiSourceMode
    val visibilityFilters = remember(
        animatedFilterActive, fabRestoreState.hideLiked, fabRestoreState.hideSaved, fabRestoreState.hideWatched,
        animatedDurationRange, state.query.applied.dateRange,
    ) {
        SearchVisibilityFilters(
            dateRange = state.query.applied.dateRange, animatedOnly = animatedFilterActive,
            hideLiked = fabRestoreState.hideLiked, hideSaved = fabRestoreState.hideSaved,
            hideWatched = fabRestoreState.hideWatched, animatedDurationRange = animatedDurationRange,
        )
    }
    val unknownDurationPolicy = unknownAnimatedDurationPolicy(resolveUnknownAnimatedDurations)
    val displayResults = remember(state.content.results, state.content.displayVersion, state.query.appliedQueryHash) {
        state.content.results
    }
    val durationMetadata = rememberDurationFilterMetadata(displayResults, durationStates, animatedDurationFilterActive, retainKey = "search")
    val acquiredDurations = durationMetadata.knownDurationMsByPostId
    val presentedResults = durationMetadata.presentedPosts ?: displayResults
    val retainedLikedPostIds = retainPostsUntilViewerCloses(remember(state.relatedPosts, pendingLikePostIds) {
        state.relatedPosts.retainedLikedPostIds + pendingLikePostIds
    })
    val visibleResults = remember(
        presentedResults, visibilityFilters, likedPostIds, retainedLikedPostIds, savedPostIds, watchedPostIds,
        unknownDurationPolicy, acquiredDurations,
    ) {
        filterSearchResults(
            presentedResults, visibilityFilters, likedPostIds, savedPostIds, watchedPostIds, unknownDurationPolicy,
            acquiredDurations, retainedLikedPostIds,
        )
    }
    val related = rememberRelatedFeedPresentation(
        pendingLikePostIds = pendingLikePostIds,
        canonicalPosts = displayResults, visibleCanonicalPosts = visibleResults,
        relatedState = state.relatedPosts, visibilityFilters = visibilityFilters,
        likedPostIds = likedPostIds, savedPostIds = savedPostIds, watchedPostIds = watchedPostIds,
        unknownAnimatedDurationPolicy = unknownDurationPolicy, durationStates = durationStates,
        durationFilterActive = animatedDurationFilterActive,
    )
    return remember(
        animatedFilterActive, animatedDurationRange, animatedDurationFilterActive, visibilityFilters,
        isNhentaiSourceMode, displayResults, durationMetadata, visibleResults, related,
    ) {
        SearchFeed(
            animatedFilterActive, animatedDurationRange, animatedDurationFilterActive, visibilityFilters,
            isNhentaiSourceMode, displayResults, durationMetadata, visibleResults, related,
        )
    }
}

/** Restores and persists the grid position, then returns the demand that loads further pages. */
@Composable
internal fun rememberSearchScrollAndPaging(
    state: SearchUiState,
    feed: SearchFeed,
    gridState: LazyStaggeredGridState,
    resolveUnknownAnimatedDurations: Boolean,
    onAction: (SearchAction) -> Unit,
): FeedPageDemandControl {
    val queryHash = state.query.appliedQueryHash
    val projection = feed.related.projection
    val scrollRestoration = (state.restoration as? SearchRestorationUiState.Restored)
        ?.takeIf { restored -> restored.scrollState != null }
    SearchScrollRestorationEffect(
        state, projection, gridState, onAction,
        visibilityReady = !feed.animatedDurationFilterActive || feed.durationMetadata.isVisibilityResolved(
            feed.displayResults.getOrNull(scrollRestoration?.scrollState?.firstVisibleItemIndex ?: -1),
            resolveInBackground = resolveUnknownAnimatedDurations,
            canonicalIndex = scrollRestoration?.scrollState?.firstVisibleItemIndex,
        ),
    )
    SearchScrollPersistenceEffect(queryHash, state.restoration, projection, gridState, onAction)
    return rememberFeedPageDemand(
        input = FeedPageDemandInput(
            contextKey = queryHash,
            completedGeneration = state.execution.lastCompletedRequestId ?: 0L,
            canonicalCount = feed.displayResults.size,
            visibleCount = feed.visibleResults.size,
            canLoadMore = state.content.canLoadMore,
            ready = state.content.hasExecutedSearch,
            refreshing = state.loading,
            paging = state.loadingMore,
            failed = state.content.error != null,
            durationsBlockPaging = feed.durationMetadata.blocksPaging,
            requiredCanonicalIndex = scrollRestoration?.scrollState?.firstVisibleItemIndex,
        ),
        filters = feed.visibilityFilters,
        gridState = gridState,
        presentedItemCount = projection.entries.size,
        canonicalIndexForVisibleItems = projection::greatestVisibleCanonicalIndex,
        onLoadNextPage = { onAction(SearchAction.LoadNextPage) },
    )
}

