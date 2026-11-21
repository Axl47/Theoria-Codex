package com.theoriacodex.app.search

import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.ui.components.pagedFeedFooterMessage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.orchestration.SourceRunState

internal fun buildEmptySearchMessage(
    sourceResults: List<Post>,
    visibilityFilters: SearchVisibilityFilters,
    loadingMore: Boolean,
    canLoadMore: Boolean,
): String? {
    if (sourceResults.isEmpty()) return if (loadingMore) "Loading more pages..." else null
    if (!visibilityFilters.animatedDurationRange.isFullRange) {
        if (loadingMore) {
            return "No animated media in the selected duration range yet. Loading more pages..."
        }
        return "No animated media found in the selected duration range."
    }
    if (visibilityFilters.animatedOnly && loadingMore) {
        return "No animated media yet. Loading more pages..."
    }
    val message = visibilityFilterMessage(visibilityFilters)
    return if (canLoadMore && message != null) "$message More pages are available." else message
}

/**
 * The results grid's footer. Filters that hide every loaded post leave no grid, so the empty state above speaks
 * instead. A failed source counts as a failure: unified paging drops it, which would otherwise read as the end.
 */
internal fun searchFooterMessage(state: SearchUiState, resolvingDurations: Boolean, hasVisibleResults: Boolean): String? =
    pagedFeedFooterMessage(
        resolvingDurations = resolvingDurations,
        hasVisibleResults = hasVisibleResults && state.content.hasExecutedSearch,
        canLoadMore = state.content.canLoadMore,
        requestRunning = state.loading || state.loadingMore,
        failed = state.content.error != null || state.content.statuses.any { it.state == SourceRunState.FAILED },
    )

private fun visibilityFilterMessage(filters: SearchVisibilityFilters): String? {
    if (filters.dateRange != null) return "No posts with a known date in this range."
    val hidden = listOfNotNull(
        "liked".takeIf { filters.hideLiked },
        "saved".takeIf { filters.hideSaved },
        "watched".takeIf { filters.hideWatched },
    )
    if (hidden.isEmpty()) return if (filters.animatedOnly) "No animated media found for the current results." else null
    val kinds = when (hidden.size) {
        1 -> hidden.single()
        2 -> hidden.joinToString(" and ")
        else -> hidden.dropLast(1).joinToString(", ") + ", and " + hidden.last()
    }
    return "No results remain after hiding $kinds posts."
}
