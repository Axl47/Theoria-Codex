package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId

@Composable
internal fun DedicatedSourceSearchView(
    dedicated: DedicatedSourceState,
    followedIds: Set<SeriesId>,
    supportsLatest: Boolean,
    oldSearchBar: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = rememberLazyGridState()
    var query by rememberSaveable(dedicated.source.key.id) { mutableStateOf((dedicated.kind as? BrowseKind.Filtered)?.text.orEmpty()) }
    var searchBarDismissed by rememberSaveable(dedicated.source.key.id) { mutableStateOf(false) }
    var focusRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(dedicated.kind) {
        query = (dedicated.kind as? BrowseKind.Filtered)?.text.orEmpty()
    }
    SeriesScrollChromeEffect(grid, onScrollStarted = { searchBarDismissed = true })
    ScrollToTopOnTabReselect(TopLevelDestination.Series) {
        grid.animateScrollToItem(0)
        searchBarDismissed = false
    }
    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize()) {
        DedicatedSourceHeader(dedicated.source, onBack = onBack)
        CollapsingSearchChrome(
            expanded = !searchBarDismissed,
            legacy = oldSearchBar,
            onExpand = { searchBarDismissed = false; focusRequest++ },
            trailingControl = {},
            horizontalPadding = 16.dp,
            contentHorizontalPadding = 0.dp,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
            inputField = {
                SeriesSearchBar(
                    query = query,
                    placeholder = "Search ${dedicated.source.sourceName}",
                    searching = dedicated.kind is BrowseKind.Filtered && dedicated.kind.text.isNotEmpty(),
                    focusRequest = focusRequest,
                    onQueryChange = { query = it },
                    onSearch = onSearch,
                    onClear = { query = ""; onSearch("") },
                )
            },
        ) {
            DedicatedSourceGridBody(
                dedicated, followedIds, grid, supportsLatest,
                besideCollapsedSearch = searchBarDismissed && !oldSearchBar,
                onChoose, onFilter, onRetry, onMore, onOpenSeries,
            )
        }
    }
}

@Composable
private fun DedicatedSourceHeader(source: SeriesSourceListing, onBack: () -> Unit) {
    SecondaryScreenAppBar(
        title = source.sourceName,
        onBack = onBack,
        subtitle = source.languageSubtitle,
        modifier = Modifier.testTag(DEDICATED_HEADER_TAG),
    )
}

@Composable
private fun DedicatedSourceGridBody(
    dedicated: DedicatedSourceState,
    followedIds: Set<SeriesId>,
    grid: LazyGridState,
    supportsLatest: Boolean,
    besideCollapsedSearch: Boolean,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    val end by animateDpAsState(if (besideCollapsedSearch) DEDICATED_SEARCH_CLEARANCE else 16.dp, label = "Listing controls end")
    ListingControls(
        kind = dedicated.kind,
        latest = supportsLatest,
        filterCount = (dedicated.kind as? BrowseKind.Filtered)?.changed ?: 0,
        onChoose = onChoose,
        onFilter = onFilter,
        modifier = Modifier.padding(start = 16.dp, end = end),
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_WIDTH),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        browseListing(dedicated.source, dedicated.run, followedIds, onRetry, onMore, onOpenSeries)
    }
}

internal const val DEDICATED_HEADER_TAG = "Dedicated source header"

private val DEDICATED_SEARCH_CLEARANCE = 72.dp
