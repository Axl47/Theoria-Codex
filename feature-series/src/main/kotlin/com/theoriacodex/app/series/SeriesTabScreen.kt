package com.theoriacodex.app.series

import com.theoriacodex.app.ui.components.ScrollChromeBox
import com.theoriacodex.app.ui.components.hidesOnScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SearchBarMinHeight
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import java.util.Locale

@Composable
fun SeriesTabScreen(
    viewModel: SeriesTabViewModel,
    onOpenSeries: (SeriesId) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
    oldSearchBar: Boolean = false,
    switchSource: ((SeriesId) -> SwitchSourceViewModel)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val open: (Series) -> Unit = { viewModel.open(it, onOpenSeries) }
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }
    LaunchedEffect(state.searchQuery) { query = state.searchQuery }
    val grids = SeriesGrids(rememberLazyGridState(), rememberLazyGridState(), rememberLazyListState())
    val dedicated = state.dedicated

    if (dedicated != null) {
        DedicatedSourceSearchView(
            dedicated = dedicated,
            followedIds = content.followedIds,
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
            content = content,
            query = query,
            onQueryChange = { query = it },
            grids = grids,
            viewModel = viewModel,
            oldSearchBar = oldSearchBar,
            onOpenSeries = open,
            onOpenExtensions = onOpenExtensions,
            hold = rememberSeriesHold(state, viewModel, onOpenSeries, switchSource),
            modifier = modifier,
        )
    }

    val sheet = state.filterSheet
    val sheetSourceName = state.dedicated?.source?.name ?: state.browse?.name
    if (sheet != null && sheetSourceName != null) {
        SeriesFilterSheet(sheetSourceName, sheet, viewModel::editFilters, onDismiss = viewModel::closeFilters, onSearch = viewModel::searchSource)
    }
}

private class SeriesGrids(val following: LazyGridState, val browse: LazyGridState, val search: LazyListState)

@Composable
private fun SeriesTabHomeContent(
    state: SeriesTabState,
    content: SeriesTabContent,
    query: String,
    onQueryChange: (String) -> Unit,
    grids: SeriesGrids,
    viewModel: SeriesTabViewModel,
    oldSearchBar: Boolean,
    onOpenSeries: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
    hold: SeriesHold,
    modifier: Modifier,
) {
    var searchBarDismissed by rememberSaveable { mutableStateOf(false) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val search = searchBinding(state, content, query, onQueryChange, viewModel) { searchBarDismissed = false }
    val extensionsButton: @Composable () -> Unit = {
        ExtensionsButton(content.missingExtensions.size, onOpenExtensions)
    }
    val scrollable = grids.shown(content.view, state.search != null)

    SeriesScrollChromeEffect(scrollable = scrollable, onScrollStarted = { searchBarDismissed = true })
    BackHandler(enabled = search.searching, onBack = search.onClear)

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
                query = search.query,
                placeholder = search.placeholder,
                searching = search.searching,
                focusRequest = focusRequest,
                onQueryChange = search.onQueryChange,
                onSearch = search.onSearch,
                onClear = search.onClear,
                trailing = { if (oldSearchBar) extensionsButton() },
            )
        },
    ) {
        ScrollChromeBox(Modifier.fillMaxSize()) {
            ScrollToTopOnTabReselect(TopLevelDestination.Series, scrollable) { searchBarDismissed = false }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SeriesViewSwitch(content.view, besideCollapsedSearch = searchBarDismissed && !oldSearchBar, onChoose = viewModel::chooseView)
                SeriesTabViewBody(state, content, grids, viewModel, onOpenSeries, onOpenExtensions, hold)
            }
        }
    }
}

private class SearchBinding(
    val query: String,
    val placeholder: String,
    val searching: Boolean,
    val onQueryChange: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onClear: () -> Unit,
)

private fun searchBinding(
    state: SeriesTabState,
    content: SeriesTabContent,
    query: String,
    onQueryChange: (String) -> Unit,
    viewModel: SeriesTabViewModel,
    onCleared: () -> Unit,
): SearchBinding = when (content.view) {
    SeriesView.FOLLOWING -> SearchBinding(
        query = content.followingQuery,
        placeholder = "Search following",
        searching = content.followingQuery.isNotEmpty(),
        onQueryChange = viewModel::filterFollowing,
        onSearch = {},
        onClear = {
            viewModel.filterFollowing("")
            onCleared()
        },
    )
    SeriesView.BROWSE -> SearchBinding(
        query = query,
        placeholder = SEARCH_ALL_SOURCES,
        searching = state.search != null,
        onQueryChange = onQueryChange,
        onSearch = viewModel::search,
        onClear = {
            onQueryChange("")
            viewModel.clearSearch()
            onCleared()
        },
    )
}

private fun SeriesGrids.shown(view: SeriesView, searching: Boolean): ScrollableState = when {
    view == SeriesView.FOLLOWING -> following
    searching -> search
    else -> browse
}

@Composable
private fun SeriesTabViewBody(
    state: SeriesTabState,
    content: SeriesTabContent,
    grids: SeriesGrids,
    viewModel: SeriesTabViewModel,
    onOpenSeries: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
    hold: SeriesHold,
) {
    val search = content.search
    when {
        content.view == SeriesView.FOLLOWING -> FollowingBody(
            content = content,
            checking = state.checking,
            found = state.found,
            failedCheck = state.failedCheck,
            grid = grids.following,
            onRefresh = viewModel::refresh,
            onRetryFailed = viewModel::retry,
            onDismissFailures = viewModel::dismissFailures,
            onUnfollow = viewModel::unfollow,
            onChooseKind = viewModel::chooseKind,
            onChooseLibrary = viewModel::chooseLibrary,
            onShowAll = viewModel::showAllFollowing,
            onSearchAll = viewModel::searchAllSources,
            onBrowse = { viewModel.chooseView(SeriesView.BROWSE) },
            onOpenSeries = onOpenSeries,
            hold = hold,
        )
        search != null -> GlobalSearchBody(
            sourcesLoaded = state.sources != null,
            results = search,
            content = content,
            onChooseKind = viewModel::chooseKind,
            listState = grids.search,
            onOpenSeries = onOpenSeries,
            onOpenSource = viewModel::openDedicatedSearch,
            onRetry = viewModel::retrySearch,
            onMore = viewModel::searchMore,
        )
        else -> BrowseBody(
            state = state,
            content = content,
            grid = grids.browse,
            supportsLatest = state.sources?.firstOrNull { it.key == content.browse?.key }?.supportsLatest == true,
            onBrowse = viewModel::browse,
            onChoose = viewModel::choose,
            onFilter = viewModel::openFilters,
            onRetry = viewModel::retryBrowse,
            onMore = viewModel::browseMore,
            onOpenSeries = onOpenSeries,
            onOpenExtensions = onOpenExtensions,
        )
    }
}

@Composable
private fun SeriesViewSwitch(view: SeriesView, besideCollapsedSearch: Boolean, onChoose: (SeriesView) -> Unit) {
    val end by animateDpAsState(if (besideCollapsedSearch) COLLAPSED_SEARCH_CLEARANCE else 16.dp, label = "Series view switch end")
    SingleChoiceSegmentedButtonRow(
        Modifier
            .hidesOnScroll()
            .fillMaxWidth()
            .heightIn(min = if (besideCollapsedSearch) SearchBarMinHeight else 0.dp)
            .padding(start = 16.dp, end = end)
            .wrapContentHeight(Alignment.CenterVertically),
    ) {
        SeriesView.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = entry == view,
                onClick = { onChoose(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, SeriesView.entries.size),
                modifier = Modifier.testTag(seriesViewTag(entry)),
                icon = {},
            ) { Text(entry.label, maxLines = 1) }
        }
    }
}

@Composable
private fun GlobalSearchBody(
    sourcesLoaded: Boolean,
    results: List<SeriesSourceListing>,
    content: SeriesTabContent,
    onChooseKind: (SeriesKind?) -> Unit,
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
        if (content.offersFilter) item(key = "kinds") { KindFilter(content.filter, onChooseKind, Modifier.padding(horizontal = 16.dp)) }
        if (!sourcesLoaded) {
            item(key = "search-waiting") { LoadingState() }
        } else {
            searchResults(results, content, onOpenSeries, onOpenSource, onRetry, onMore)
        }
    }
}

@Composable
internal fun KindFilter(chosen: SeriesKind?, onChoose: (SeriesKind?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChoiceChip("All", selected = chosen == null) { onChoose(null) }
        SeriesKind.entries.forEach { kind -> FilterChoiceChip(kind.label, selected = chosen == kind) { onChoose(kind) } }
    }
}

internal fun LazyGridScope.browseListing(
    browse: SeriesSourceListing,
    run: Int,
    followedIds: Set<SeriesId>,
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
            items(listing.series, key = { it.id.toString() }) { series ->
                SeriesCard(series, { open(series) }, followed = series.id in followedIds)
            }
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
    content: SeriesTabContent,
    open: (Series) -> Unit,
    openSource: (SourceKey) -> Unit,
    retry: (SourceKey) -> Unit,
    more: (SourceKey) -> Unit,
) {
    if (results.isEmpty()) item(key = "search-no-sources") { EmptyState(noExtensionsOn(content.filter)) }
    val languages = results.distinctBy { it.language }.size > 1
    results.forEach { result ->
        val listing = result.listing
        item(key = "search-title-${result.key.id}") {
            SourceSearchHeader(
                title = result.sourceName,
                subtitle = result.languageSubtitle.takeIf { languages },
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
                                SeriesCard(series, { open(series) }, Modifier.width(CARD_WIDTH), followed = series.id in content.followedIds, marked = content.marked)
                            }
                        }
                }
            }
        }
    }
}

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

private fun moreResults(listing: SeriesListing.Loaded, result: SeriesSourceListing, onMore: () -> Unit): (@Composable () -> Unit)? = when {
    listing.loadingMore -> { { CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp), strokeWidth = 2.dp) } }
    listing.moreFailed -> { { SourceLoadFailure(LOAD_FAILED, result.key, result.name, onMore, Modifier.widthIn(max = FAILURE_WIDTH), compact = true) } }
    listing.nextPage != null -> { { TextButton(onClick = onMore) { Text("More") } } }
    else -> null
}

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
internal fun FailureRow(
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    message: String = LOAD_FAILED,
    actionLabel: String = "Retry",
    style: TextStyle = LocalTextStyle.current,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f, fill = false), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(actionLabel) }
    }
}

internal fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

internal val CARD_WIDTH = 104.dp
private val COLLAPSED_SEARCH_CLEARANCE = 128.dp
private val FAILURE_WIDTH = 280.dp
private fun noExtensionsOn(kind: SeriesKind?): String =
    kind?.let { "No ${it.label.lowercase(Locale.getDefault())} extensions on" } ?: "No extensions on"

internal fun seriesViewTag(view: SeriesView) = "series-view-${view.name.lowercase()}"

@Composable
private fun ExtensionsButton(missing: Int, onClick: () -> Unit) {
    BadgedBox(
        badge = {
            if (missing > 0) {
                Badge(Modifier.offset(x = -BADGE_INSET, y = BADGE_DROP)) {
                    Text(if (missing > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else "$missing", Modifier.clearAndSetSemantics {})
                }
            }
        },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { if (missing > 0) stateDescription = missingExtensionsLine(missing) }) {
            Icon(Icons.Default.Extension, contentDescription = EXTENSIONS_TITLE)
        }
    }
}

private const val MAX_BADGE_COUNT = 99
private val BADGE_INSET = 16.dp
private val BADGE_DROP = 14.dp
