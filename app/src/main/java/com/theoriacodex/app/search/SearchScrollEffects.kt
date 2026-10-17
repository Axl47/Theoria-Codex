package com.theoriacodex.app.search

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.allowsScrollPersistence
import com.theoriacodex.app.search.state.canApplyScrollRestoration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance

@Composable
internal fun SearchScrollRestorationEffect(
    state: SearchUiState,
    projection: RelatedFeedProjection,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    val currentProjection by rememberUpdatedState(projection)
    val restoration = state.restoration as? SearchRestorationUiState.Restored
    val currentRestoration by rememberUpdatedState(restoration)
    val dispatch by rememberUpdatedState(onAction)
    // A deliberate drag supersedes restoration; scrollToItem emits no DragInteraction.
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().collect {
            currentRestoration?.takeIf { it.scrollState != null }?.let {
                dispatch(SearchAction.ScrollRestorationApplied(it.scrollRequestId))
            }
        }
    }
    LaunchedEffect(restoration?.scrollRequestId, state.canApplyScrollRestoration) {
        val request = restoration ?: return@LaunchedEffect
        val restored = request.scrollState ?: return@LaunchedEffect
        if (!state.canApplyScrollRestoration) return@LaunchedEffect
        val activeProjection = currentProjection
        if (activeProjection.entries.isNotEmpty()) {
            gridState.scrollToItem(
                index = activeProjection.gridIndexForCanonicalIndex(restored.firstVisibleItemIndex),
                scrollOffset = restored.firstVisibleItemOffsetPx.coerceAtLeast(0),
            )
        }
        dispatch(SearchAction.ScrollRestorationApplied(request.scrollRequestId))
    }
}

@Composable
internal fun SearchScrollPersistenceEffect(
    queryHash: String,
    restoration: SearchRestorationUiState,
    animatedFilterActive: Boolean,
    projection: RelatedFeedProjection,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    val currentProjection by rememberUpdatedState(projection)
    val dispatch by rememberUpdatedState(onAction)
    LaunchedEffect(queryHash, animatedFilterActive, restoration.allowsScrollPersistence) {
        if (animatedFilterActive || !restoration.allowsScrollPersistence) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                currentProjection.canonicalPositionForGridIndex(index, offset)?.let { position ->
                    dispatch(SearchAction.ScrollChanged(position.index, position.offsetPx))
                }
            }
    }
}
