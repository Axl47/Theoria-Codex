package com.theoriacodex.app.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.undo.DeferredRemovalRow
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.app.ui.undo.SurfaceLeaveEffect

/**
 * What the offline rows can do; [remove] and [removeAll] are deferred destruction with Undo in the sheet, and
 * [actOnRemoval] is a pending removal's Undo or Retry (see [OfflineStorageUiState.pendingRemovals]).
 */
class OfflineRowActions(
    val retry: (owner: String) -> Unit,
    val cancel: (owner: String) -> Unit,
    val remove: (owner: String) -> Unit,
    val removeAll: () -> Unit,
    val actOnRemoval: (key: String) -> Unit,
)

/**
 * The Library's one sheet for offline Codices (with progress, Cancel, Retry, Remove) above exported
 * downloads. Opened for a Codex ([markedCodexId]), it scrolls to and marks that Codex's row. Each pending removal
 * draws its Undo where the item was ([downloadRemovals], [OfflineStorageUiState.pendingRemovals]); leaving the
 * sheet calls [onLeave], which commits them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTransfersSheet(
    offline: OfflineStorageUiState,
    offlineActions: OfflineRowActions,
    batches: List<PostDownloadBatch>,
    downloadRemovals: DownloadRemovalRows,
    downloadActions: DownloadTileActions,
    markedCodexId: String?,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SurfaceLeaveEffect(onLeave = onLeave)
        val content = TransfersSheetContent(offline, batches, downloadRemovals.pending)
        val marked = markedCodexId?.let(::codexOfflineOwner)
        val grid = rememberLazyGridState()
        LaunchedEffect(marked) {
            val row = offline.owners.indexOfFirst { it.id == marked }
            if (row >= 0) grid.scrollToItem(content.offlineRowsStart + row)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp).testTag("library-transfers"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!content.showOffline && !content.showDownloads) fullLine("empty") { EmptyState("Nothing offline or downloaded") }
            if (content.showOffline) offlineSection(offline, offlineActions, marked)
            if (content.showDownloads) downloadsSection(content, batches, downloadRemovals, downloadActions)
        }
    }
}

/** The pending download removals and the action of each one's row (Undo, or Retry after a failure). */
class DownloadRemovalRows(val pending: Map<DownloadRemoval, PendingRemoval>, val act: (DownloadRemoval) -> Unit)

/**
 * What the sheet lists given the pending removals: a download removed from its tile keeps that slot, drawn as
 * Undo; section-wide removals draw under their section's header.
 */
private class TransfersSheetContent(
    offline: OfflineStorageUiState,
    batches: List<PostDownloadBatch>,
    downloadRemovals: Map<DownloadRemoval, PendingRemoval>,
) {
    private val items = batches.asReversed().flatMap { it.items }
    private val hidden = downloadRemovals.keys.flatMapTo(mutableSetOf()) { it.ids }

    /** Each download removed from its own tile, by id: its row takes that tile's place. */
    val tileRemovals = downloadRemovals.keys.filter { it.anchor != DownloadsUndoAnchor }
        .flatMap { removal -> removal.ids.map { it to removal } }.toMap()
    val visibleItems = items.filter { it.id !in hidden }
    val shownItems = items.filter { it.id !in hidden || it.id in tileRemovals }
    val bulkRemovals = downloadRemovals.keys.filter { it.anchor == DownloadsUndoAnchor }
    val showOffline = offline.owners.isNotEmpty() || offline.pendingRemovals.isNotEmpty() || offline.message != null
    val showDownloads = shownItems.isNotEmpty() || bulkRemovals.isNotEmpty()

    /** The grid index of the first offline owner row: after the header, any message and any Undo rows. */
    val offlineRowsStart = 1 + listOfNotNull(offline.message).size + offline.pendingRemovals.size
}

private fun LazyGridScope.downloadsSection(
    content: TransfersSheetContent,
    batches: List<PostDownloadBatch>,
    removals: DownloadRemovalRows,
    actions: DownloadTileActions,
) {
    fullLine("downloads-header") { DownloadsHeader(batches, content.visibleItems, actions) }
    content.bulkRemovals.forEach { removal ->
        fullLine("downloads-undo:${removal.ids.sorted()}") { removals.RemovalRow(removal) }
    }
    items(content.shownItems, key = { "download:${it.id}" }) { item ->
        val removal = content.tileRemovals[item.id]
        if (removal != null) {
            removals.RemovalRow(removal)
        } else {
            DownloadItemCard(item, batches.firstOrNull { batch -> batch.items.any { it.id == item.id } }, actions)
        }
    }
}

private fun LazyGridScope.offlineSection(
    offline: OfflineStorageUiState,
    actions: OfflineRowActions,
    marked: String?,
) {
    fullLine("offline-header") { OfflineHeader(enabled = offline.owners.isNotEmpty(), onRemoveAll = actions.removeAll) }
    offline.message?.let { message ->
        fullLine("offline-message") { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
    offline.pendingRemovals.forEach { (key, removal) ->
        fullLine("offline-undo:$key") { DeferredRemovalRow(removal, onAct = { actions.actOnRemoval(key) }) }
    }
    items(offline.owners, key = { "offline:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { owner ->
        OfflineOwnerRow(owner, offline.controlsEnabled, marked = owner.id == marked, actions)
    }
}

@Composable
private fun DownloadRemovalRows.RemovalRow(removal: DownloadRemoval) {
    pending[removal]?.let { DeferredRemovalRow(it, onAct = { act(removal) }) }
}

private fun LazyGridScope.fullLine(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun OfflineHeader(enabled: Boolean, onRemoveAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Offline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { expanded = true }, enabled = enabled) {
                Icon(Icons.Default.MoreVert, contentDescription = "Manage all offline copies")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Remove all offline copies") }, onClick = { expanded = false; onRemoveAll() })
            }
        }
    }
}
