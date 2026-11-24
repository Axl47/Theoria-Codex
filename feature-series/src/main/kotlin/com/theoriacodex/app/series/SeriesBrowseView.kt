package com.theoriacodex.app.series

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind

/**
 * The Browse view: the browsed source's listing under one header, which picks the source (every source that is on,
 * grouped by kind) and its listing. [supportsLatest] says whether the source has a Latest listing.
 */
@Composable
internal fun BrowseBody(
    state: SeriesTabState,
    content: SeriesTabContent,
    grid: LazyGridState,
    supportsLatest: Boolean,
    onBrowse: (SourceKey) -> Unit,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val sources = content.sources
    val browse = content.browse
    var picking by rememberSaveable { mutableStateOf(false) }
    when {
        sources == null -> LoadingState()
        sources.isEmpty() -> EmptyState("No extensions on", actionLabel = EXTENSIONS_TITLE, onAction = onOpenExtensions)
        else -> Column(Modifier.fillMaxSize()) {
            if (browse != null) {
                BrowseHeader(
                    browse = browse,
                    kind = state.browseKind,
                    latest = supportsLatest,
                    onPick = { picking = true },
                    onChoose = onChoose,
                    onFilter = onFilter,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CARD_WIDTH),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (browse != null) browseListing(browse, state.browseRun, content.followedIds, onRetry, onMore, onOpenSeries)
            }
        }
    }
    if (picking && !sources.isNullOrEmpty()) {
        SourcePickerSheet(sources, browse?.key, onPick = { onBrowse(it); picking = false }, onDismiss = { picking = false })
    }
}

/** The browsed source, which opens the source picker, then its listing's controls. */
@Composable
private fun BrowseHeader(
    browse: SeriesSourceListing,
    kind: BrowseKind,
    latest: Boolean,
    onPick: () -> Unit,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.weight(1f, fill = false).testTag(BROWSED_SOURCE_TAG),
            contentPadding = PaddingValues(start = 12.dp, end = 8.dp),
        ) {
            if (browse.key.seriesKind == SeriesKind.ANIME) {
                Icon(Icons.Default.PlayArrow, contentDescription = SeriesKind.ANIME.label, Modifier.size(ButtonDefaults.IconSize))
            }
            Text(browse.name, Modifier.weight(1f, fill = false).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        ListingControls(kind, latest, (kind as? BrowseKind.Filtered)?.active ?: 0, onChoose, onFilter, Modifier.weight(1f, fill = false))
    }
}

/**
 * Popular and, when the source has it, Latest, then the source's filters, counting the [filterCount] changed. Neither
 * listing is chosen while a filtered search shows. Browse and a source's dedicated search share them.
 */
@Composable
internal fun ListingControls(
    kind: BrowseKind,
    latest: Boolean,
    filterCount: Int,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listings = if (latest) listOf(BrowseKind.Popular to "Popular", BrowseKind.Latest to "Latest") else listOf(BrowseKind.Popular to "Popular")
    Row(modifier.testTag(LISTING_CONTROLS_TAG), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SingleChoiceSegmentedButtonRow {
            listings.forEachIndexed { index, (listing, label) ->
                SegmentedButton(
                    selected = kind == listing,
                    onClick = { onChoose(listing) },
                    shape = SegmentedButtonDefaults.itemShape(index, listings.size),
                    // No checkmark, so segments keep their width when the selection changes.
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
        }
        IconButton(onClick = onFilter) {
            BadgedBox(badge = { if (filterCount > 0) Badge { Text("$filterCount") } }) {
                Icon(Icons.Default.FilterList, contentDescription = FILTER)
            }
        }
    }
}

/** Every source that is on, under its kind when both kinds are on, each with its language; [selected] is checked. */
@Composable
private fun SourcePickerSheet(sources: List<SeriesSource>, selected: SourceKey?, onPick: (SourceKey) -> Unit, onDismiss: () -> Unit) {
    val groups = sources.groupBy { it.kind }.toSortedMap()
    ExpandedBottomSheet(onDismiss = onDismiss) {
        SheetHeader("Source", closeDescription = "Close", onDismiss = onDismiss)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            groups.forEach { (kind, members) ->
                if (groups.size > 1) {
                    item(key = "kind-${kind.name}") {
                        Text(
                            kind.label,
                            Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(members, key = { it.key.id }) { source -> SourceRow(source, source.key == selected) { onPick(source.key) } }
            }
        }
    }
}

@Composable
private fun SourceRow(source: SeriesSource, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(languageName(source.language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Browsing", tint = color)
    }
}

internal const val BROWSED_SOURCE_TAG = "Series browsed source"
internal const val LISTING_CONTROLS_TAG = "Series listing controls"
