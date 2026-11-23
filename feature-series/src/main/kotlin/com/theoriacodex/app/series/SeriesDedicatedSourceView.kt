package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.rememberQuickReturn
import com.theoriacodex.domain.series.Series

/**
 * One source's dedicated search, opened by tapping a source header in global search. Keeps the same collapsing pill
 * search bar and quick-return floating browse controls as the main Series tab.
 */
@Composable
internal fun DedicatedSourceSearchView(
    dedicated: DedicatedSourceState,
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
    var controlsVisible by remember(dedicated.source.key.id) { mutableStateOf(true) }

    LaunchedEffect(dedicated.kind) {
        query = (dedicated.kind as? BrowseKind.Filtered)?.text.orEmpty()
        controlsVisible = true
    }
    SeriesScrollChromeEffect(grid, onScrollStarted = { searchBarDismissed = true }, onAtTop = { controlsVisible = true })
    ScrollToTopOnTabReselect(TopLevelDestination.Series) {
        searchBarDismissed = false
        controlsVisible = true
        grid.animateScrollToItem(0)
    }
    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize()) {
        SecondaryScreenAppBar(dedicated.source.sourceName, onBack = onBack, subtitle = dedicated.source.languageSubtitle)
        CollapsingSearchChrome(
            expanded = !searchBarDismissed,
            legacy = oldSearchBar,
            onExpand = {
                searchBarDismissed = false
                focusRequest++
            },
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
                    onClear = {
                        query = ""
                        onSearch("")
                    },
                )
            },
        ) {
            DedicatedSourceGridBody(
                dedicated = dedicated,
                grid = grid,
                supportsLatest = supportsLatest,
                controlsVisible = controlsVisible,
                onRevealControls = { controlsVisible = it },
                onChoose = onChoose,
                onFilter = onFilter,
                onRetry = onRetry,
                onMore = onMore,
                onOpenSeries = onOpenSeries,
            )
        }
    }
}

@Composable
private fun DedicatedSourceGridBody(
    dedicated: DedicatedSourceState,
    grid: LazyGridState,
    supportsLatest: Boolean,
    controlsVisible: Boolean,
    onRevealControls: (Boolean) -> Unit,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    val quickReturn = rememberQuickReturn(onRevealControls)
    Box(Modifier.fillMaxSize().nestedScroll(quickReturn)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CARD_WIDTH),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BROWSE_GRID_BOTTOM_PADDING),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            browseListing(dedicated.source, dedicated.run, onRetry, onMore, onOpenSeries)
        }
        BrowseControlsBar(
            kind = dedicated.kind,
            latest = supportsLatest,
            visible = controlsVisible,
            onChoose = onChoose,
            onFilter = onFilter,
            filterCount = (dedicated.kind as? BrowseKind.Filtered)?.changed ?: 0,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
