package com.theoriacodex.app.search

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.search.state.ScrollStep
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.allowsScrollPersistence
import com.theoriacodex.app.search.state.openScrollRequestId
import com.theoriacodex.app.search.state.scrollStep
import com.theoriacodex.app.search.state.scrollStepAfterJump
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
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
    val gridWasShowingPosts = remember(restoration?.scrollRequestId) {
        Snapshot.withoutReadObservation { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }
    }
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().collect {
            currentRestoration?.let { supersedeScrollRequest(it, dispatch) }
        }
    }
    val canRestore =
        state.scrollStep(visibilityReady, projection.entries.size, gridWasShowingPosts = false) != ScrollStep.Wait
    LaunchedEffect(restoration?.scrollRequestId, canRestore, projection.entries.size) {
        val activeProjection = currentProjection
        when (val step = state.scrollStep(visibilityReady, activeProjection.entries.size, gridWasShowingPosts)) {
            ScrollStep.Wait -> Unit
            is ScrollStep.Finish -> dispatch(SearchAction.ScrollRestorationApplied(step.requestId))
            is ScrollStep.Jump -> {
                val index = activeProjection.gridIndexForCanonicalIndex(step.anchorIndex)
                gridState.scrollToItem(index, step.offsetPx)
                val reached = gridState.firstVisibleItemIndex == index &&
                    gridState.firstVisibleItemScrollOffset == step.offsetPx
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

internal fun supersedeScrollRequest(restoration: SearchRestorationUiState, onAction: (SearchAction) -> Unit) {
    restoration.openScrollRequestId?.let { onAction(SearchAction.ScrollRestorationApplied(it)) }
}

@Composable
internal fun SearchTabReselectEffect(
    restoration: SearchRestorationUiState,
    gridState: LazyStaggeredGridState,
    onAction: (SearchAction) -> Unit,
) {
    ScrollToTopOnTabReselect(TopLevelDestination.Search) {
        supersedeScrollRequest(restoration, onAction)
        gridState.animateScrollToItem(0)
    }
}

internal suspend fun resetSearchScroll(
    visibleResults: List<Post>,
    gridState: LazyStaggeredGridState,
    restoration: SearchRestorationUiState,
    onAction: (SearchAction) -> Unit,
) {
    supersedeScrollRequest(restoration, onAction)
    if (visibleResults.isNotEmpty()) {
        runCatchingPreservingCancellation {
            gridState.scrollToItem(index = 0, scrollOffset = 0)
        }
    }
    onAction(SearchAction.ScrollChanged(0, 0))
}
