package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.rememberQuickReturn
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId

/**
 * One source's dedicated search, opened by tapping a source header in global search. Both the source header and the
 * floating browse controls hide on downward scroll and return on upward scroll, alongside the collapsing search bar.
 */
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
    var chromeVisible by remember(dedicated.source.key.id) { mutableStateOf(true) }

    LaunchedEffect(dedicated.kind) {
        query = (dedicated.kind as? BrowseKind.Filtered)?.text.orEmpty()
        chromeVisible = true
    }
    SeriesScrollChromeEffect(grid, onScrollStarted = { searchBarDismissed = true }, onAtTop = { chromeVisible = true })
    ScrollToTopOnTabReselect(TopLevelDestination.Series) {
        searchBarDismissed = false
        chromeVisible = true
        grid.animateScrollToItem(0)
    }
    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize()) {
        DedicatedSourceHeader(dedicated.source, visible = chromeVisible || !searchBarDismissed, onBack = onBack)
        CollapsingSearchChrome(
            expanded = !searchBarDismissed,
            legacy = oldSearchBar,
            onExpand = { searchBarDismissed = false; chromeVisible = true; focusRequest++ },
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
                dedicated, followedIds, grid, supportsLatest, chromeVisible, { chromeVisible = it }, onChoose, onFilter, onRetry, onMore, onOpenSeries,
            )
        }
    }
}

@Composable
private fun DedicatedSourceHeader(source: SeriesSourceListing, visible: Boolean, onBack: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(TheoriaMotion.MEDIUM_MS)) + fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = shrinkVertically(tween(TheoriaMotion.MEDIUM_MS)) + fadeOut(tween(TheoriaMotion.SHORT_MS)),
    ) {
        SecondaryScreenAppBar(
            title = source.sourceName,
            onBack = onBack,
            subtitle = source.languageSubtitle,
            modifier = Modifier.testTag(DEDICATED_HEADER_TAG),
        )
    }
}

@Composable
private fun DedicatedSourceGridBody(
    dedicated: DedicatedSourceState,
    followedIds: Set<SeriesId>,
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
            browseListing(dedicated.source, dedicated.run, followedIds, onRetry, onMore, onOpenSeries)
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

internal const val DEDICATED_HEADER_TAG = "Dedicated source header"
