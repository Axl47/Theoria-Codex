package com.theoriacodex.app.triage

import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SearchAnimationFilterRestoreState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey

/**
 * The visibility filters the grid behind [source] applies, rebuilt from that grid's saved filter-sheet state [fab],
 * so a triage deck shows the posts the grid shows. [searchQuery] is Search's applied query. Keep in step with the
 * grids' own filters: Search's `rememberSearchFeed`, For You's `rememberForYouFeed` and `rememberCreatorFeed`.
 */
fun gridVisibilityFilters(source: ViewerStreamSource, fab: FeedFabRestoreState, searchQuery: Query?): SearchVisibilityFilters =
    when (source) {
        ViewerStreamSource.SEARCH -> searchVisibilityFilters(fab, searchQuery)
        ViewerStreamSource.FOR_YOU -> SearchVisibilityFilters(
            animatedOnly = fab.animatedOnly,
            animatedDurationRange = AnimatedDurationRange(fab.durationMinBucket, fab.durationMaxBucket),
        )
        ViewerStreamSource.CREATOR_PROFILE -> SearchVisibilityFilters(
            animatedOnly = fab.animatedOnly,
            hideLiked = fab.hideLiked,
            hideSaved = fab.hideSaved,
            animatedDurationRange = AnimatedDurationRange(fab.durationMinBucket, fab.durationMaxBucket),
        )
        else -> SearchVisibilityFilters()
    }

/** Search keeps its animation filter per source (or for Unified), and nhentai galleries are never filtered by it. */
private fun searchVisibilityFilters(fab: FeedFabRestoreState, query: Query?): SearchVisibilityFilters {
    val mode = query?.mode ?: QueryMode.Unified
    val scope = (mode as? QueryMode.Source)?.source?.id ?: UNIFIED_ANIMATION_SCOPE
    val animation = fab.searchAnimationByScope[scope] ?: SearchAnimationFilterRestoreState()
    val nhentai = mode == QueryMode.Source(SourceKey.NHENTAI)
    return SearchVisibilityFilters(
        animatedOnly = animation.animatedOnly && !nhentai,
        hideLiked = fab.hideLiked,
        hideSaved = fab.hideSaved,
        hideWatched = fab.hideWatched,
        animatedDurationRange = if (nhentai) AnimatedDurationRange.Full
        else AnimatedDurationRange(animation.durationMinBucket, animation.durationMaxBucket),
        dateRange = query?.dateRange,
    )
}

private const val UNIFIED_ANIMATION_SCOPE = "UNIFIED"
