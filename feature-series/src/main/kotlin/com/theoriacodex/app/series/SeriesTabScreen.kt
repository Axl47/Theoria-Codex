package com.theoriacodex.app.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId

/**
 * The Series tab: a search across every source, the followed series, and one source's popular series to browse. A
 * search replaces the shelf and the browse grid with one row of results per source until it is cleared. Pulling down
 * checks the followed series for new chapters.
 */
@Composable
fun SeriesTabScreen(viewModel: SeriesTabViewModel, onOpenSeries: (SeriesId) -> Unit, onOpenExtensions: () -> Unit, modifier: Modifier = Modifier) = BrowserCheckScope {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val open: (Series) -> Unit = { viewModel.open(it, onOpenSeries) }
    val clearSearch = {
        query = ""
        viewModel.clearSearch()
    }
    ScrollToTopOnTabReselect(TopLevelDestination.Series) { grid.animateScrollToItem(0) }
    BackHandler(enabled = state.search != null, onBack = clearSearch)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SeriesSearchField(query, searching = state.search != null, Modifier.weight(1f), onQueryChange = { query = it }, onSearch = viewModel::search, onClear = clearSearch)
            IconButton(onClick = onOpenExtensions) { Icon(Icons.Default.Extension, contentDescription = EXTENSIONS_TITLE) }
        }
        CompositionLocalProvider(LocalSeriesSources provides state.sources.orEmpty()) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CARD_WIDTH),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val search = state.search
                    if (search != null && state.sources == null) fullWidth("search-waiting") { LoadingState() }
                    else if (search != null) searchResults(search, open, viewModel::retrySearch, viewModel::searchMore)
                    else {
                        followingShelf(followed, open)
                        browse(state, viewModel, open, onOpenExtensions)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesSearchField(
    query: String,
    searching: Boolean,
    modifier: Modifier,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search all sources") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty() || searching) IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Clear") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focus.clearFocus()
                onSearch(query)
            },
        ),
    )
}

private fun LazyGridScope.followingShelf(followed: List<FollowedSeries>, open: (Series) -> Unit) {
    if (followed.isEmpty()) return
    fullWidth("following-title") { SectionTitle("Following") }
    fullWidth("following") {
        SeriesRow(followed, key = { it.series.id.toString() }) { item -> SeriesCard(item.series, { open(item.series) }, Modifier.width(CARD_WIDTH), item.unreadCount) }
    }
}

private fun LazyGridScope.browse(state: SeriesTabState, viewModel: SeriesTabViewModel, open: (Series) -> Unit, onOpenExtensions: () -> Unit) {
    val sources = state.sources
    when {
        sources == null -> fullWidth("sources-loading") { LoadingState() }
        sources.isEmpty() -> fullWidth("sources-empty") { EmptyState(NO_EXTENSIONS_ON, actionLabel = EXTENSIONS_TITLE, onAction = onOpenExtensions) }
        else -> {
            fullWidth("browse-title") { SectionTitle("Browse") }
            fullWidth("browse-sources") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources, key = { it.key.id }) { source ->
                        FilterChoiceChip(source.name, selected = source.key == state.browse?.key) { viewModel.browse(source.key) }
                    }
                }
            }
            state.browse?.let { browse -> browseListing(browse, viewModel, open) }
        }
    }
}

private fun LazyGridScope.browseListing(browse: SeriesSourceListing, viewModel: SeriesTabViewModel, open: (Series) -> Unit) {
    when (val listing = browse.listing) {
        SeriesListing.Loading -> fullWidth("browse-loading") { LoadingState() }
        is SeriesListing.Failed -> fullWidth("browse-failed") {
            SourceLoadFailure(listing.message, listing.browserCheck, browse.name, onRetry = { viewModel.browse(browse.key) })
        }
        is SeriesListing.Loaded -> {
            if (listing.series.isEmpty()) fullWidth("browse-empty") { EmptyState("No series") }
            items(listing.series, key = { it.id.toString() }) { series -> SeriesCard(series, { open(series) }) }
            when {
                listing.moreFailed -> fullWidth("browse-more-failed") {
                    SourceLoadFailure(LOAD_FAILED, listing.moreCheck, browse.name, viewModel::browseMore, compact = true)
                }
                listing.nextPage != null -> fullWidth("browse-more") {
                    LaunchedEffect(listing.nextPage) { viewModel.browseMore() }
                    LoadingState()
                }
            }
        }
    }
}

private fun LazyGridScope.searchResults(
    results: List<SeriesSourceListing>,
    open: (Series) -> Unit,
    retry: (SourceKey) -> Unit,
    more: (SourceKey) -> Unit,
) {
    if (results.isEmpty()) fullWidth("search-no-sources") { EmptyState(NO_EXTENSIONS_ON) }
    results.forEach { result ->
        val listing = result.listing
        fullWidth("search-title-${result.key.id}") {
            SectionTitle(result.name, count = (listing as? SeriesListing.Loaded)?.series?.size)
        }
        fullWidth("search-${result.key.id}") {
            when (listing) {
                SeriesListing.Loading -> LoadingState()
                is SeriesListing.Failed -> SourceLoadFailure(listing.message, listing.browserCheck, result.name, { retry(result.key) }, compact = true)
                is SeriesListing.Loaded ->
                    if (listing.series.isEmpty()) Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else SeriesRow(listing.series, key = { it.id.toString() }, after = moreResults(listing, result.name) { more(result.key) }) { series ->
                        SeriesCard(series, { open(series) }, Modifier.width(CARD_WIDTH))
                    }
            }
        }
    }
}

/** What ends a source's row of results: its next page's button or progress; null once the source has no more. */
private fun moreResults(listing: SeriesListing.Loaded, sourceName: String, onMore: () -> Unit): (@Composable () -> Unit)? = when {
    listing.loadingMore -> { { CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp), strokeWidth = 2.dp) } }
    // Bounded, so the line can wrap at a row's end.
    listing.moreCheck != null -> { { SourceLoadFailure(LOAD_FAILED, listing.moreCheck, sourceName, onMore, Modifier.widthIn(max = CHECK_WIDTH), compact = true) } }
    listing.nextPage != null -> { { TextButton(onClick = onMore) { Text(if (listing.moreFailed) "Retry" else "More") } } }
    else -> null
}

/** A sideways row of cards, running to the screen's edges past the grid's padding, with [after] at its end. */
@Composable
private fun <T> SeriesRow(items: List<T>, key: (T) -> Any, after: (@Composable () -> Unit)? = null, card: @Composable (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items(items, key = key) { item -> card(item) }
        if (after != null) item(key = "after") { after() }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int? = null) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One line for a source that failed, where a full-height failure would push the other sources away. */
@Composable
internal fun FailureRow(onRetry: () -> Unit, modifier: Modifier = Modifier, message: String = LOAD_FAILED, actionLabel: String = "Retry") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(actionLabel) }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private val CARD_WIDTH = 104.dp
private val CHECK_WIDTH = 280.dp
private const val NO_EXTENSIONS_ON = "No extensions on"
