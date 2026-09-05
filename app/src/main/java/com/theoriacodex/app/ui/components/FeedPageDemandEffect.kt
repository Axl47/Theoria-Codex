package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.theoriacodex.app.feed.FeedPageDemand
import com.theoriacodex.app.feed.FeedPageDemandInput
import com.theoriacodex.app.search.SearchVisibilityFilters
import kotlinx.coroutines.flow.collect

class FeedPageDemandControl internal constructor(
    val showContinue: Boolean,
    val onContinue: () -> Unit,
)

/** Observe the same canonical mapping used by feed navigation without making presentation rows anchors. */
@Composable
fun rememberFeedPageDemand(
    input: FeedPageDemandInput,
    filters: SearchVisibilityFilters,
    gridState: LazyStaggeredGridState,
    presentedItemCount: Int = input.visibleCount,
    canonicalIndexForVisibleItems: (List<Int>) -> Int?,
    onLoadNextPage: () -> Unit,
): FeedPageDemandControl {
    val owner = remember(filters) { FeedPageDemand() }
    val currentInput by rememberUpdatedState(input)
    val currentItemCount by rememberUpdatedState(presentedItemCount)
    val currentCanonicalIndex by rememberUpdatedState(canonicalIndexForVisibleItems)
    val loadNextPage by rememberUpdatedState(onLoadNextPage)
    var continueRevision by remember { mutableIntStateOf(0) }
    var showContinue by remember(owner) { mutableStateOf(false) }

    LaunchedEffect(owner, gridState) {
        snapshotFlow {
            val indices = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            currentInput.copy(
                hasLocalFilters = filters.animatedOnly || filters.hideLiked || filters.hideSaved ||
                    filters.hideWatched || !filters.animatedDurationRange.isFullRange,
                lastVisibleCanonicalIndex = currentCanonicalIndex(indices),
                atVisibleEnd = currentItemCount > 0 && indices.any { it >= currentItemCount - 1 },
                scrolling = gridState.isScrollInProgress,
            ) to continueRevision
        }.collect { (snapshot, _) ->
            val result = owner.update(snapshot)
            showContinue = result.showContinue
            if (result.loadNextPage) loadNextPage()
        }
    }
    return FeedPageDemandControl(showContinue) {
        owner.continueLoading()
        continueRevision += 1
    }
}

@Composable
fun FeedContinueLoading(control: FeedPageDemandControl) {
    if (control.showContinue) {
        TextButton(onClick = control.onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue loading")
        }
    }
}
