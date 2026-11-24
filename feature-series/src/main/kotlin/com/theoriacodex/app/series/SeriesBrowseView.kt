package com.theoriacodex.app.series

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.LoadingState
import com.theoriacodex.app.ui.components.SheetHeader
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.languageName

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
    ListingControls(kind, latest, (kind as? BrowseKind.Filtered)?.active ?: 0, onChoose, onFilter, modifier) {
        SourceChip(browse.name, onPick, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun SourceChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = "Change source", role = Role.DropdownList, onClick = onClick)
            .padding(start = 12.dp, end = 4.dp)
            .testTag(BROWSED_SOURCE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, Modifier.size(18.dp))
    }
}

@Composable
internal fun ListingControls(
    kind: BrowseKind,
    latest: Boolean,
    filterCount: Int,
    onChoose: (BrowseKind) -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    val listings = when {
        latest -> listOf(BrowseKind.Popular to "Popular", BrowseKind.Latest to "Latest")
        kind is BrowseKind.Filtered -> listOf(BrowseKind.Popular to "Popular")
        else -> emptyList()
    }
    Row(modifier.fillMaxWidth().testTag(LISTING_CONTROLS_TAG), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            listings.forEach { (listing, label) -> FilterChoiceChip(label, selected = kind == listing) { onChoose(listing) } }
        }
        IconButton(onClick = onFilter) {
            BadgedBox(badge = { if (filterCount > 0) Badge { Text("$filterCount") } }) {
                Icon(Icons.Default.FilterList, contentDescription = FILTER)
            }
        }
    }
}

@Composable
private fun SourcePickerSheet(sources: List<SeriesSource>, selected: SourceKey?, onPick: (SourceKey) -> Unit, onDismiss: () -> Unit) {
    val groups = sources.groupBy { it.kind }.toSortedMap()
    val languages = sources.distinctBy { it.language }.size > 1
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
                items(members, key = { it.key.id }) { source -> SourceRow(source, source.key == selected, languages) { onPick(source.key) } }
            }
        }
    }
}

@Composable
private fun SourceRow(source: SeriesSource, selected: Boolean, language: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (language) Text(languageName(source.language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Browsing", tint = color)
    }
}

internal const val BROWSED_SOURCE_TAG = "Series browsed source"
internal const val LISTING_CONTROLS_TAG = "Series listing controls"
