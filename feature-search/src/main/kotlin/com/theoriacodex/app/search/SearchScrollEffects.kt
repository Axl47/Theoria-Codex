package com.theoriacodex.app.search

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.search.state.ScrollStep
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.allowsScrollPersistence
import com.theoriacodex.app.search.state.scrollStep
import com.theoriacodex.app.search.state.scrollStepAfterJump
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
    // Only the state rules wait, so the grid facts passed here cannot change whether a step is ready.
    val canRestore = state.scrollStep(visibilityReady, projection.entries.size, gridLaidOut = false) != ScrollStep.Wait
    // A retry needs the posts that arrived, which can reach the projection after the state that loaded them.
    LaunchedEffect(restoration?.scrollRequestId, canRestore, projection.entries.size) {
        val activeProjection = currentProjection
        // Sampled, not observed: the jump's own layout must not restart the effect.
        val gridLaidOut = gridState.layoutInfo.visibleItemsInfo.isNotEmpty()
        when (val step = state.scrollStep(visibilityReady, activeProjection.entries.size, gridLaidOut)) {
            ScrollStep.Wait -> Unit
            is ScrollStep.Finish -> dispatch(SearchAction.ScrollRestorationApplied(step.requestId))
            is ScrollStep.Jump -> {
                val index = activeProjection.gridIndexForCanonicalIndex(step.anchorIndex)
                gridState.scrollToItem(index, step.offsetPx)
                val reached = gridState.firstVisibleItemIndex == index &&
                    gridState.firstVisibleItemScrollOffset == step.offsetPx
                // An open request lets the list end ask for the next page, whose posts retry the jump.
                val after = state.scrollStepAfterJump(reached, gridState.canScrollForward)
                if (after is ScrollStep.Finish) dispatch(SearchAction.ScrollRestorationApplied(after.requestId))
            }
        }
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
        // Filtering can replace the post at an unchanged grid index and offset. Each persist writes saved
        // state, so mid-scroll only a new leading item is reported; the exact offset follows once it settles.
        snapshotFlow {
            val offset = if (gridState.isScrollInProgress) {
                Snapshot.withoutReadObservation { gridState.firstVisibleItemScrollOffset }
            } else {
                gridState.firstVisibleItemScrollOffset
            }
            currentProjection.canonicalPositionForGridIndex(gridState.firstVisibleItemIndex, offset)
        }
            .distinctUntilChanged()
            .collect { position ->
                if (position != null) {
                    dispatch(SearchAction.ScrollChanged(position.index, position.offsetPx))
                }
            }
    }
}
