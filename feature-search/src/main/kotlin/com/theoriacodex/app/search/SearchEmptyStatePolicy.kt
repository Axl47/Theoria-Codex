package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Post

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

private fun visibilityFilterMessage(filters: SearchVisibilityFilters): String? {
    if (filters.dateRange != null) return "No posts with a known date in this range."
    if (!filters.hideLiked && !filters.hideSaved && !filters.hideWatched) {
        return if (filters.animatedOnly) "No animated media found for the current results." else null
    }
    return when {
        filters.hideLiked && filters.hideSaved && filters.hideWatched ->
            "No results remain after hiding liked, saved, and watched posts."
        filters.hideLiked && filters.hideSaved -> "No results remain after hiding liked and saved posts."
        filters.hideWatched && !filters.hideLiked && !filters.hideSaved ->
            "No results remain after hiding watched posts."
        filters.hideLiked -> "No results remain after hiding liked posts."
        filters.hideSaved -> "No results remain after hiding saved posts."
        else -> "No results remain after applying the current visibility filters."
    }
}
