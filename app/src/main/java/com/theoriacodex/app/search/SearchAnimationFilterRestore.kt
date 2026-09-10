package com.theoriacodex.app.search

import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SearchAnimationFilterRestoreState
import com.theoriacodex.domain.model.QueryMode

internal fun FeedFabRestoreState.searchAnimationFilter(
    mode: QueryMode,
): SearchAnimationFilterRestoreState {
    return searchAnimationByScope[mode.animationFilterScopeKey()]
        ?.let { state ->
            state.copy(
                durationMinBucket = state.durationMinBucket.coerceIn(0, 25),
                durationMaxBucket = state.durationMaxBucket.coerceIn(0, 25),
            )
        }
        ?: SearchAnimationFilterRestoreState()
}

internal fun FeedFabRestoreState.withSearchAnimationFilter(
    mode: QueryMode,
    transform: (SearchAnimationFilterRestoreState) -> SearchAnimationFilterRestoreState,
): FeedFabRestoreState {
    val scope = mode.animationFilterScopeKey()
    return copy(searchAnimationByScope = searchAnimationByScope + (scope to transform(searchAnimationFilter(mode))))
}

private fun QueryMode.animationFilterScopeKey(): String = when (this) {
    QueryMode.Unified -> "UNIFIED"
    is QueryMode.Source -> source.name
}
