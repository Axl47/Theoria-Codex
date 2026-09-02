package com.theoriacodex.app.search

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SearchScrollRestorationEffect(
    restoration: SearchRestorationUiState.Restored?,
    hasVisibleResults: Boolean,
    animatedFilterActive: Boolean,
    projection: RelatedFeedProjection,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    val currentProjection by rememberUpdatedState(projection)
    LaunchedEffect(restoration?.scrollRequestId, hasVisibleResults, animatedFilterActive) {
        val request = restoration ?: return@LaunchedEffect
        val restored = request.scrollState ?: return@LaunchedEffect
        if (animatedFilterActive || !hasVisibleResults) return@LaunchedEffect
        val activeProjection = currentProjection
        val lastIndex = activeProjection.entries.lastIndex.coerceAtLeast(0)
        gridState.scrollToItem(
            index = activeProjection.gridIndexForCanonicalIndex(restored.firstVisibleItemIndex)
                .coerceIn(0, lastIndex),
            scrollOffset = restored.firstVisibleItemOffsetPx.coerceAtLeast(0),
        )
        onAction(SearchAction.ScrollRestorationApplied(request.scrollRequestId))
    }
}

@Composable
internal fun SearchScrollPersistenceEffect(
    queryHash: String,
    animatedFilterActive: Boolean,
    projection: RelatedFeedProjection,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    val currentProjection by rememberUpdatedState(projection)
    LaunchedEffect(queryHash, animatedFilterActive) {
        if (animatedFilterActive) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                currentProjection.canonicalPositionForGridIndex(index, offset)?.let { position ->
                    onAction(SearchAction.ScrollChanged(position.index, position.offsetPx))
                }
            }
    }
}
