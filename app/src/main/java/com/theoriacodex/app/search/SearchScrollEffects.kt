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
    visibilityReady: Boolean = true,
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
    val canRestore = state.canApplyScrollRestoration && (restoration?.resetToTop == true || visibilityReady)
    LaunchedEffect(restoration?.scrollRequestId, canRestore) {
        val request = restoration ?: return@LaunchedEffect
        val restored = request.scrollState ?: return@LaunchedEffect
        if (!canRestore) return@LaunchedEffect
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
    projection: RelatedFeedProjection,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    val currentProjection by rememberUpdatedState(projection)
    val dispatch by rememberUpdatedState(onAction)
    LaunchedEffect(queryHash, restoration.allowsScrollPersistence) {
        if (!restoration.allowsScrollPersistence) return@LaunchedEffect
        // Filtering can replace the post at an unchanged grid index and offset.
        snapshotFlow {
            currentProjection.canonicalPositionForGridIndex(
                gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
            )
        }
            .distinctUntilChanged()
            .collect { position ->
                if (position != null) {
                    dispatch(SearchAction.ScrollChanged(position.index, position.offsetPx))
                }
            }
    }
}
