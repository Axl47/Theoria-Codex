package com.theoriacodex.app.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesKind

/**
 * The Following view: the followed series as [content] orders and narrows them, under the kind filter and Sort &
 * filter. Pulling down checks them for new entries; when sources fail, [failedCheck] says so above the grid with Retry.
 * With nothing followed it leads to Browse; with nothing shown, a title search goes on to every source, otherwise Show
 * all clears the filters.
 */
@Composable
internal fun FollowingBody(
    content: SeriesTabContent,
    refreshing: Boolean,
    failedCheck: FollowedCheck?,
    grid: LazyGridState,
    onRefresh: () -> Unit,
    onChooseKind: (SeriesKind?) -> Unit,
    onChooseLibrary: (LibraryOptions) -> Unit,
    onShowAll: () -> Unit,
    onSearchAll: () -> Unit,
    onBrowse: () -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    val following = content.following
    Column(Modifier.fillMaxSize()) {
        if (content.followsAny) {
            FollowingControls(content, onChooseKind, onOpenOptions = { optionsOpen = true }, Modifier.padding(horizontal = 16.dp))
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_WIDTH),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (failedCheck != null && content.followsAny) {
                    fullWidth("following-check-failed") { FailureRow(onRefresh, message = failedCheck.message) }
                }
                when {
                    following == null -> Unit
                    following.isEmpty() && content.followingQuery.isNotBlank() ->
                        fullWidth("following-no-match") { NoMatches(content.followingQuery, onShowAll, onSearchAll) }
                    !content.followsAny -> fullWidth("following-none") { EmptyState("Nothing followed", actionLabel = SeriesView.BROWSE.label, onAction = onBrowse) }
                    following.isEmpty() -> fullWidth("following-no-match") { NoMatches(content.followingQuery, onShowAll, onSearchAll) }
                    else -> items(following, key = { it.series.id.toString() }) { item ->
                        SeriesCard(item.series, { onOpenSeries(item.series) }, unread = item.unreadCount, marked = content.marked)
                    }
                }
            }
        }
    }
    if (optionsOpen) {
        LibraryOptionsSheet(content.library, content.words, onChoose = onChooseLibrary, onDismiss = { optionsOpen = false })
    }
}

/** "Couldn't check 2 series", or when none could be checked, "Couldn't check for updates". */
internal val FollowedCheck.message: String
    get() = if (failed == checked) "Couldn't check for updates" else "Couldn't check $failed series"

/** The kind filter, when both kinds are followed or on, and Sort & filter, marked while a filter hides series. */
@Composable
private fun FollowingControls(content: SeriesTabContent, onChooseKind: (SeriesKind?) -> Unit, onOpenOptions: () -> Unit, modifier: Modifier = Modifier) {
    val filtered = content.library.show != LibraryShow.ALL
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (content.offersFilter) KindFilter(content.filter, onChooseKind, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenOptions, modifier = Modifier.semantics { if (filtered) stateDescription = "Filtered" }) {
            BadgedBox(badge = { if (filtered) Badge() }) {
                Icon(Icons.Default.FilterList, contentDescription = SORT_AND_FILTER)
            }
        }
    }
}

/** Nothing followed matches: a title search goes on to every source, otherwise Show all clears the filters. */
@Composable
private fun NoMatches(query: String, onShowAll: () -> Unit, onSearchAll: () -> Unit) {
    if (query.isBlank()) {
        EmptyState("No matches", actionLabel = "Show all", onAction = onShowAll)
    } else {
        EmptyState("No matches", actionLabel = SEARCH_ALL_SOURCES, onAction = onSearchAll)
    }
}

@Composable
private fun LibraryOptionsSheet(options: LibraryOptions, words: EntryWords, onChoose: (LibraryOptions) -> Unit, onDismiss: () -> Unit) {
    SortFilterSheet(
        filters = LibraryShow.entries.map { SheetChoice(it, it.label(words)) },
        filter = options.show,
        onFilter = { onChoose(options.copy(show = it)) },
        sorts = LibrarySort.entries.map { SheetChoice(it, it.label(words)) },
        sort = options.sort,
        onSort = { onChoose(options.copy(sort = it)) },
        sortMark = Icons.Default.Check,
        sortMarkDescription = null,
        onReset = { onChoose(LibraryOptions()) }.takeUnless { options.isDefault },
        onDismiss = onDismiss,
    )
}

internal const val SORT_AND_FILTER = "Sort and filter"
internal const val SEARCH_ALL_SOURCES = "Search all sources"
