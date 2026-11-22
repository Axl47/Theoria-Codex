package com.theoriacodex.app.search

import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.ui.components.rememberFeedView
import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.components.FeedDurations
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.DurationFilterMetadata
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.RelatedFeedPresentation
import com.theoriacodex.app.ui.components.rememberFeedPageDemand
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/** What the results grid shows after visibility filters, known durations, and related posts apply. */
class SearchFeed(
    val animatedFilterActive: Boolean,
    val animatedDurationRange: AnimatedDurationRange,
    val isNhentaiSourceMode: Boolean,
    val displayResults: List<Post>,
    val view: FeedView,
) {
    val animatedDurationFilterActive: Boolean get() = view.durationFilterActive
    val visibilityFilters: SearchVisibilityFilters get() = view.filters
    val durationMetadata: DurationFilterMetadata get() = view.durations
    val visibleResults: List<Post> get() = view.visiblePosts
    val related: RelatedFeedPresentation get() = view.related
}

@Composable
fun rememberSearchFeed(
    state: SearchUiState,
    relatedState: RelatedPostsUiState,
    fabRestoreState: FeedFabRestoreState,
    marks: FeedMarks,
    durations: FeedDurations,
): SearchFeed {
    val animationRestoreState = fabRestoreState.searchAnimationFilter(state.query.draft.mode)
    val durationMinBucket = animationRestoreState.durationMinBucket
    val durationMaxBucket = animationRestoreState.durationMaxBucket
    val isNhentaiSourceMode = state.query.draft.mode == QueryMode.Source(SourceKey.NHENTAI)
    // The results on screen answer the applied query, so its media decides the phone filter, as its date range does.
    val animatedFilterActive = state.query.applied.hidesStillsOnPhone
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(minBucket = durationMinBucket, maxBucket = durationMaxBucket)
    }
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
    val displayResults = remember(state.content.results, state.content.displayVersion, state.query.appliedQueryHash) {
        state.content.results
    }
    val view = rememberFeedView(
        retainKey = "search", posts = displayResults, filters = visibilityFilters, durations = durations,
        relatedState = relatedState, marks = marks,
        durationFilterActive = !animatedDurationRange.isFullRange && !isNhentaiSourceMode,
    )
    return remember(animatedFilterActive, animatedDurationRange, isNhentaiSourceMode, displayResults, view) {
        SearchFeed(animatedFilterActive, animatedDurationRange, isNhentaiSourceMode, displayResults, view)
    }
}

/** Restores and persists the grid position, then returns the demand that loads further pages. */
@Composable
fun rememberSearchScrollAndPaging(
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

