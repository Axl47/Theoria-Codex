package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.rememberQuickReturn
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId

/**
 * The Series tab: a collapsing search across every source, the followed series, and one source's popular or latest
 * series (or filtered search) to browse. A search across sources replaces the shelf and browse grid with one section
 * per source; tapping a source's header opens its dedicated search. Pulling down checks followed series for updates.
 */
@Composable
fun SeriesTabScreen(
    viewModel: SeriesTabViewModel,
    onOpenSeries: (SeriesId) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
    oldSearchBar: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val open: (Series) -> Unit = { viewModel.open(it, onOpenSeries) }
    // Hoisted above the dedicated-source switch so returning from a source's search keeps the query and scroll positions.
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val searchList = rememberLazyListState()
    val dedicated = state.dedicated

    CompositionLocalProvider(LocalSeriesSources provides state.sources.orEmpty()) {
        if (dedicated != null) {
            DedicatedSourceSearchView(
                dedicated = dedicated,
                supportsLatest = state.sources?.firstOrNull { it.key == dedicated.source.key }?.supportsLatest == true,
                oldSearchBar = oldSearchBar,
                onBack = viewModel::closeDedicatedSearch,
                onSearch = viewModel::searchDedicated,
                onChoose = viewModel::chooseDedicated,
                onFilter = viewModel::openFilters,
                onRetry = viewModel::retryDedicated,
                onMore = viewModel::dedicatedMore,
                onOpenSeries = open,
                modifier = modifier,
            )
        } else {
            SeriesTabHomeContent(
                state = state,
                followed = followed,
                query = query,
                onQueryChange = { query = it },
                grid = grid,
                searchList = searchList,
                viewModel = viewModel,
                oldSearchBar = oldSearchBar,
                onOpenSeries = open,
                onOpenExtensions = onOpenExtensions,
                modifier = modifier,
            )
        }
    }

    val sheet = state.filterSheet
    val sheetSourceName = state.dedicated?.source?.name ?: state.browse?.name
    if (sheet != null && sheetSourceName != null) {
        SeriesFilterSheet(sheetSourceName, sheet, viewModel::editFilters, onDismiss = viewModel::closeFilters, onSearch = viewModel::searchSource)
    }
}

@Composable
private fun SeriesTabHomeContent(
    state: SeriesTabState,
    followed: List<FollowedSeries>,
    query: String,
    onQueryChange: (String) -> Unit,
    grid: LazyGridState,
    searchList: LazyListState,
    viewModel: SeriesTabViewModel,
    oldSearchBar: Boolean,
    onOpenSeries: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier,
) {
    var searchBarDismissed by rememberSaveable { mutableStateOf(false) }
    var focusRequest by remember { mutableIntStateOf(0) }
    var browseControlsVisible by remember { mutableStateOf(true) }
    val clearSearch = {
        onQueryChange("")
        searchBarDismissed = false
        viewModel.clearSearch()
    }
    val extensionsButton: @Composable () -> Unit = {
        IconButton(onClick = onOpenExtensions) { Icon(Icons.Default.Extension, contentDescription = EXTENSIONS_TITLE) }
    }

    LaunchedEffect(state.browse?.key, state.browseKind) { browseControlsVisible = true }
    SeriesScrollChromeEffect(
        scrollable = if (state.search != null) searchList else grid,
        onScrollStarted = { searchBarDismissed = true },
        onAtTop = { browseControlsVisible = true },
    )
    ScrollToTopOnTabReselect(TopLevelDestination.Series) {
        searchBarDismissed = false
        browseControlsVisible = true
        if (state.search != null) searchList.animateScrollToItem(0) else grid.animateScrollToItem(0)
    }
    BackHandler(enabled = state.search != null, onBack = clearSearch)

    CollapsingSearchChrome(
        expanded = !searchBarDismissed,
        legacy = oldSearchBar,
        onExpand = {
            searchBarDismissed = false
            focusRequest++
        },
        trailingControl = extensionsButton,
        horizontalPadding = 16.dp,
        contentHorizontalPadding = 0.dp,
        modifier = modifier.fillMaxSize().padding(top = if (oldSearchBar) 6.dp else 0.dp),
        inputField = {
            SeriesSearchBar(
                query = query,
                placeholder = "Search all sources",
                searching = state.search != null,
                focusRequest = focusRequest,
                onQueryChange = onQueryChange,
                onSearch = viewModel::search,
                onClear = clearSearch,
                trailing = { if (oldSearchBar) extensionsButton() },
            )
        },
    ) {
        SeriesTabBody(state, followed, grid, searchList, browseControlsVisible, { browseControlsVisible = it }, viewModel, onOpenSeries, onOpenExtensions)
    }
}

@Composable
private fun SeriesTabBody(
    state: SeriesTabState,
    followed: List<FollowedSeries>,
    grid: LazyGridState,
    searchList: LazyListState,
    controlsVisible: Boolean,
    onRevealControls: (Boolean) -> Unit,
    viewModel: SeriesTabViewModel,
    onOpenSeries: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val search = state.search
    if (search != null) {
        GlobalSearchBody(
            sourcesLoaded = state.sources != null,
            results = search,
            listState = searchList,
            onOpenSeries = onOpenSeries,
            onOpenSource = viewModel::openDedicatedSearch,
            onRetry = viewModel::retrySearch,
            onMore = viewModel::searchMore,
        )
    } else {
        BrowseTabBody(state, followed, grid, controlsVisible, rememberQuickReturn(onRevealControls), viewModel, onOpenSeries, onOpenExtensions)
    }
}

@Composable
private fun BrowseTabBody(
    state: SeriesTabState,
    followed: List<FollowedSeries>,
    grid: LazyGridState,
    controlsVisible: Boolean,
    quickReturn: NestedScrollConnection,
    viewModel: SeriesTabViewModel,
    onOpenSeries: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val browsed = state.browse
    val supportsLatest = state.sources?.firstOrNull { it.key == browsed?.key }?.supportsLatest == true
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().nestedScroll(quickReturn)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_WIDTH),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (browsed != null) BROWSE_GRID_BOTTOM_PADDING else 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                followingShelf(followed, onOpenSeries)
                browse(state, viewModel, onOpenSeries, onOpenExtensions)
            }
            if (browsed != null) {
                BrowseControlsBar(
                    kind = state.browseKind,
                    latest = supportsLatest,
                    visible = controlsVisible,
                    onChoose = viewModel::choose,
                    onFilter = viewModel::openFilters,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchBody(
    sourcesLoaded: Boolean,
    results: List<SeriesSourceListing>,
    listState: LazyListState,
    onOpenSeries: (Series) -> Unit,
    onOpenSource: (SourceKey) -> Unit,
    onRetry: (SourceKey) -> Unit,
    onMore: (SourceKey) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!sourcesLoaded) {
            item(key = "search-waiting") { LoadingState() }
        } else {
            searchResults(results, onOpenSeries, onOpenSource, onRetry, onMore)
        }
    }
}

private fun LazyGridScope.followingShelf(followed: List<FollowedSeries>, open: (Series) -> Unit) {
    if (followed.isEmpty()) return
    fullWidth("following-title") { SectionTitle("Following") }
    fullWidth("following") {
        SeriesRow(
            items = followed,
            key = { it.series.id.toString() },
            modifier = Modifier.bleedHorizontal(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) { item ->
            SeriesCard(item.series, { open(item.series) }, Modifier.width(CARD_WIDTH), item.unreadCount)
        }
    }
}

private fun LazyGridScope.browse(
    state: SeriesTabState,
    viewModel: SeriesTabViewModel,
    open: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val sources = state.sources
    when {
        sources == null -> fullWidth("sources-loading") { LoadingState() }
        sources.isEmpty() -> fullWidth("sources-empty") { EmptyState(NO_EXTENSIONS_ON, actionLabel = EXTENSIONS_TITLE, onAction = onOpenExtensions) }
        else -> {
            fullWidth("browse-title") { SectionTitle("Browse") }
            fullWidth("browse-sources") {
                LazyRow(
                    modifier = Modifier.bleedHorizontal(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sources, key = { it.key.id }) { source ->
                        FilterChoiceChip(source.label(sources), selected = source.key == state.browse?.key) { viewModel.browse(source.key) }
                    }
                }
            }
            state.browse?.let { browse ->
                browseListing(browse, state.browseRun, viewModel::retryBrowse, viewModel::browseMore, open)
            }
        }
    }
}

/** [run] keys the next page's request, so a listing that replaced another with the same next page still asks for it. */
internal fun LazyGridScope.browseListing(
    browse: SeriesSourceListing,
    run: Int,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    open: (Series) -> Unit,
) {
    when (val listing = browse.listing) {
        SeriesListing.Loading -> fullWidth("browse-loading") { LoadingState() }
        is SeriesListing.Failed -> fullWidth("browse-failed") {
            SourceLoadFailure(listing.message, browse.key, browse.name, onRetry = onRetry)
        }
        is SeriesListing.Loaded -> {
            if (listing.series.isEmpty()) fullWidth("browse-empty") { EmptyState("No series") }
            items(listing.series, key = { it.id.toString() }) { series -> SeriesCard(series, { open(series) }) }
            when {
                listing.moreFailed -> fullWidth("browse-more-failed") {
                    SourceLoadFailure(LOAD_FAILED, browse.key, browse.name, onMore, compact = true)
                }
                listing.nextPage != null -> fullWidth("browse-more") {
                    LaunchedEffect(run, listing.nextPage) { onMore() }
                    LoadingState()
                }
            }
        }
    }
}

private fun LazyListScope.searchResults(
    results: List<SeriesSourceListing>,
    open: (Series) -> Unit,
    openSource: (SourceKey) -> Unit,
    retry: (SourceKey) -> Unit,
    more: (SourceKey) -> Unit,
) {
    if (results.isEmpty()) item(key = "search-no-sources") { EmptyState(NO_EXTENSIONS_ON) }
    results.forEach { result ->
        val listing = result.listing
        item(key = "search-title-${result.key.id}") {
            SourceSearchHeader(
                title = result.sourceName,
                subtitle = result.languageSubtitle,
                onClick = { openSource(result.key) },
            )
        }
        item(key = "search-${result.key.id}") {
            Box(Modifier.padding(bottom = 8.dp)) {
                when (listing) {
                    SeriesListing.Loading -> LoadingState()
                    is SeriesListing.Failed -> SourceLoadFailure(
                        listing.message,
                        result.key,
                        result.name,
                        { retry(result.key) },
                        Modifier.padding(horizontal = 16.dp),
                        compact = true,
                    )
                    is SeriesListing.Loaded ->
                        if (listing.series.isEmpty()) {
                            Text(
                                "No results",
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SeriesRow(
                                items = listing.series,
                                key = { it.id.toString() },
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                after = moreResults(listing, result) { more(result.key) },
                            ) { series ->
                                SeriesCard(series, { open(series) }, Modifier.width(CARD_WIDTH))
                            }
                        }
                }
            }
        }
    }
}

/** Section header for a source in global search: name above language subtitle, with an arrow leading to its search. */
@Composable
private fun SourceSearchHeader(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Search $title", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What ends a source's row of results: its next page's button, progress or failure; null once the source has no more. */
private fun moreResults(listing: SeriesListing.Loaded, result: SeriesSourceListing, onMore: () -> Unit): (@Composable () -> Unit)? = when {
    listing.loadingMore -> { { CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp), strokeWidth = 2.dp) } }
    // Bounded, so the line can wrap at a row's end.
    listing.moreFailed -> { { SourceLoadFailure(LOAD_FAILED, result.key, result.name, onMore, Modifier.widthIn(max = FAILURE_WIDTH), compact = true) } }
    listing.nextPage != null -> { { TextButton(onClick = onMore) { Text("More") } } }
    else -> null
}

/** A sideways row of cards, with [after] at its end. */
@Composable
private fun <T> SeriesRow(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    after: (@Composable () -> Unit)? = null,
    card: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items, key = key) { item -> card(item) }
        if (after != null) item(key = "after") { after() }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

/** One line for a source that failed, where a full-height failure would push the other sources away. */
@Composable
internal fun FailureRow(onRetry: () -> Unit, modifier: Modifier = Modifier, message: String = LOAD_FAILED, actionLabel: String = "Retry") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(actionLabel) }
    }
}

/** Extends a full-width item inside a padded grid to reach the screen's horizontal edges. */
private fun Modifier.bleedHorizontal(padding: Dp): Modifier = layout { measurable, constraints ->
    val extra = padding.roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra * 2))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-extra, 0)
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

internal val CARD_WIDTH = 104.dp
internal val BROWSE_GRID_BOTTOM_PADDING = 80.dp
private val FAILURE_WIDTH = 280.dp
private const val NO_EXTENSIONS_ON = "No extensions on"
