package com.theoriacodex.app.search

import com.theoriacodex.domain.model.Post
import kotlin.math.abs

internal fun buildEmptySearchMessage(
    sourceResults: List<Post>,
    visibilityFilters: SearchVisibilityFilters,
    loadingMore: Boolean,
    canLoadMore: Boolean,
): String? {
    if (!visibilityFilters.animatedDurationRange.isFullRange && sourceResults.isNotEmpty()) {
        if (loadingMore || canLoadMore) {
            return "No animated media in the selected duration range yet. Retrying with more pages..."
        }
        return "No animated media found in the selected duration range."
    }
    if (visibilityFilters.animatedOnly && sourceResults.isNotEmpty() && (loadingMore || canLoadMore)) {
        return "No animated media yet. Retrying with more pages..."
    }
    if (!visibilityFilters.animatedOnly &&
        !visibilityFilters.hideLiked &&
        !visibilityFilters.hideSaved &&
        !visibilityFilters.hideWatched
    ) {
        return null
    }
    if (sourceResults.isEmpty()) return null
    return when {
        visibilityFilters.animatedOnly &&
            !visibilityFilters.hideLiked &&
            !visibilityFilters.hideSaved &&
            !visibilityFilters.hideWatched ->
            "No animated media found for the current results."
        visibilityFilters.hideLiked && visibilityFilters.hideSaved && visibilityFilters.hideWatched ->
            "No results remain after hiding liked, saved, and watched posts."
        visibilityFilters.hideLiked && visibilityFilters.hideSaved ->
            "No results remain after hiding liked and saved posts."
        visibilityFilters.hideWatched && !visibilityFilters.hideLiked && !visibilityFilters.hideSaved ->
            "No results remain after hiding watched posts."
        visibilityFilters.hideLiked -> "No results remain after hiding liked posts."
        visibilityFilters.hideSaved -> "No results remain after hiding saved posts."
        else -> "No results remain after applying the current visibility filters."
    }
}

internal fun inferPreset(fromEpochMs: Long?, toEpochMs: Long?): DateRangePreset {
    if (fromEpochMs == null || toEpochMs == null) return DateRangePreset.NONE
    val spanMs = abs(toEpochMs - fromEpochMs)
    val dayMs = 24L * 60L * 60L * 1000L
    return when {
        spanMs <= dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.TODAY
        spanMs <= 7L * dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.LAST_7_DAYS
        spanMs <= 30L * dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.LAST_30_DAYS
        else -> DateRangePreset.NONE
    }
}
