package com.theoriacodex.app.series

import com.theoriacodex.app.ui.components.hidesOnScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.SortLabel
import com.theoriacodex.app.ui.components.SortOption
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoOffer
import com.theoriacodex.data.repository.DownloadChoice
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.domain.model.SortChoice
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind

@Composable
internal fun FollowingBody(
    content: SeriesTabContent,
    checking: CheckProgress?,
    found: List<SeriesUpdate>,
    failedCheck: FollowedCheck?,
    checkRun: Int,
    grid: LazyGridState,
    onRefresh: () -> Unit,
    onRetryFailed: (Set<SeriesId>) -> Unit,
    onDismissFailures: () -> Unit,
    onUnfollow: (SeriesId) -> Unit,
    onChooseKind: (SeriesKind?) -> Unit,
    onChooseLibrary: (LibraryOptions) -> Unit,
    onShowAll: () -> Unit,
    onSearchAll: () -> Unit,
    onBrowse: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    hold: SeriesHold,
    failureTools: CheckFailureTools = CheckFailureTools(),
) {
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var failuresOpen by rememberSaveable { mutableStateOf(false) }
    var switching by remember { mutableStateOf<Series?>(null) }
    val failed = failedCheck?.stillFollowed(content.followedIds)?.takeIf { it.failed > 0 }
    ScrollToTopOnSortChange(content.library.sort.takeIf { content.following != null }, grid)
    OffScreenFailuresNotice(checkRun, failed, grid, failureTools.snackbar)
    Column(Modifier.fillMaxSize()) {
        if (content.followsAny) {
            FollowingControls(
                content,
                onChooseKind,
                onReverse = { onChooseLibrary(content.library.reversed()) },
                onOpenOptions = { optionsOpen = true },
                Modifier.hidesOnScroll().padding(horizontal = 16.dp),
            )
        }
        FollowingRefreshBox(checking, found, content.checkWords, onRefresh, Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_WIDTH),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (failed != null) {
                    fullWidth(CHECK_FAILED_KEY) {
                        CheckFailedCard(failed.message, onOpen = { failuresOpen = true }, onRetry = onRefresh, onDismiss = onDismissFailures)
                    }
                }
                followingCards(content, onShowAll, onSearchAll, onBrowse, onOpenSeries, hold) { switching = it }
            }
        }
    }
    LaunchedEffect(failed == null) { if (failed == null) failuresOpen = false }
    val from = switching
    val switchSource = failureTools.switchSource
    if (from != null && switchSource != null) {
        SwitchingSource(from, switchSource, onOpenSeries = { id ->
            failuresOpen = false
            failureTools.onOpenSeries(id)
        }) { switching = null }
    } else if (failuresOpen && failed != null) {
        val switchable: (Series) -> Boolean = { series -> switchSource != null && content.sources.orEmpty().switchTargets(series.id).isNotEmpty() }
        FollowedFailuresSheet(failed, content, FailedTitleActions(onUnfollow, onOpenSeries, switchable) { switching = it }, onRetry = { ids ->
            failuresOpen = false
            onRetryFailed(ids)
        }) { failuresOpen = false }
    }
    if (optionsOpen) {
        LibraryOptionsSheet(content.library, content.words, onChoose = onChooseLibrary, onDismiss = { optionsOpen = false })
    }
}

private fun LazyGridScope.followingCards(
    content: SeriesTabContent,
    onShowAll: () -> Unit,
    onSearchAll: () -> Unit,
    onBrowse: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    hold: SeriesHold,
    onSwitchSource: (Series) -> Unit,
) {
    val following = content.following
    when {
        following == null -> Unit
        following.isEmpty() && content.followingQuery.isNotBlank() ->
            fullWidth("following-no-match") { NoMatches(content.followingQuery, onShowAll, onSearchAll) }
        !content.followsAny -> fullWidth("following-none") { EmptyState("Nothing followed", actionLabel = SeriesView.BROWSE.label, onAction = onBrowse) }
        following.isEmpty() -> fullWidth("following-no-match") { NoMatches(content.followingQuery, onShowAll, onSearchAll) }
        else -> items(following, key = { it.series.id.toString() }) { item ->
            HeldSeriesCard(item, content.marked, hold, item.cardCounts(content.library.show, content.downloads), onOpen = { onOpenSeries(item.series) }, onSwitchSource = { onSwitchSource(item.series) })
        }
    }
}

internal class SeriesHold(
    val actions: (FollowedSeries) -> SeriesActions,
    val canDownload: (FollowedSeries) -> Boolean,
    val onMarkAllRead: (SeriesId, Boolean) -> Unit,
    val onRefresh: (SeriesId) -> Unit,
    val onDownload: (SeriesId, DownloadChoice) -> Unit,
)

@Composable
internal fun rememberSeriesHold(
    state: SeriesTabState,
    viewModel: SeriesTabViewModel,
    canSwitchSource: Boolean,
): SeriesHold {
    val undo = LocalUndoSlot.current
    return SeriesHold(
        actions = { item ->
            val actions = item.actions(state.sources, checking = state.checking != null)
            actions.copy(canSwitchSource = actions.canSwitchSource && canSwitchSource)
        },
        canDownload = viewModel::canDownload,
        onMarkAllRead = { id, read ->
            viewModel.markAllRead(id, read) { done ->
                undo.offer(undo.scope, UndoOffer(done.label, UndoAnchor.App) { viewModel.undoBulkRead(id, done) })
            }
        },
        onRefresh = { id -> viewModel.retry(setOf(id)) },
        onDownload = viewModel::download,
    )
}

@Composable
private fun HeldSeriesCard(item: FollowedSeries, marked: Boolean, hold: SeriesHold, counts: CardCounts, onOpen: () -> Unit, onSwitchSource: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val id = item.series.id
    Box {
        SeriesCard(item.series, onOpen, counts = counts, marked = marked, onLongClick = { held = true })
        SeriesHoldMenu(
            expanded = held,
            actions = hold.actions(item),
            canDownload = hold.canDownload(item),
            onChoose = SeriesActionHandlers({ hold.onMarkAllRead(id, it) }, { hold.onRefresh(id) }, onSwitchSource)::run,
            onDownload = { hold.onDownload(id, it) },
            onDismiss = { held = false },
        )
    }
}

internal val FollowedCheck.message: String
    get() = if (failed == checked) "Couldn't check for updates" else "Couldn't check $failed series"

@Composable
private fun ScrollToTopOnSortChange(sort: SortChoice<LibrarySort>?, grid: LazyGridState) {
    var arrangedSort by remember { mutableStateOf(sort) }
    LaunchedEffect(sort) {
        val before = arrangedSort
        if (sort == null) return@LaunchedEffect
        arrangedSort = sort
        if (before != null && before != sort) grid.scrollToItem(0)
    }
}

@Composable
private fun FollowingControls(
    content: SeriesTabContent,
    onChooseKind: (SeriesKind?) -> Unit,
    onReverse: () -> Unit,
    onOpenOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = content.library.show != LibraryShow.ALL
    val sort = content.library.sort
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (content.offersFilter) KindFilter(content.filter, onChooseKind, spacing = 4.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            val name = sort.key.label(content.words)
            SortLabel(
                sort.key.shortLabel(content.words),
                sort.direction,
                onReverse,
                modifier = Modifier.semantics { contentDescription = name },
                contentPadding = PaddingValues(start = 4.dp),
            )
        }
        IconButton(onClick = onOpenOptions, modifier = Modifier.semantics { if (filtered) stateDescription = "Filtered" }) {
            BadgedBox(badge = { if (filtered) Badge() }) {
                Icon(Icons.Default.FilterList, contentDescription = SORT_AND_FILTER)
            }
        }
    }
}

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
        filters = LibraryShow.entries.map { SortOption(it, it.label(words)) },
        filter = options.show,
        onFilter = { onChoose(options.copy(show = it)) },
        sorts = LibrarySort.entries.map { SortOption(it, it.label(words)) },
        sort = options.sort,
        onSort = { onChoose(options.picking(it)) },
        onReset = { onChoose(LibraryOptions()) }.takeUnless { options.isDefault },
        onDismiss = onDismiss,
    )
}

internal const val SORT_AND_FILTER = "Sort and filter"
internal const val SEARCH_ALL_SOURCES = "Search all sources"
