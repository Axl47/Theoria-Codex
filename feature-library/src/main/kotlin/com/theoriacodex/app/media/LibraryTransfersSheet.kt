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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import com.theoriacodex.app.ui.undo.UndoLeaveEffect
import com.theoriacodex.app.ui.undo.UndoRow

/** What the offline rows can do; [remove] and [removeAll] are deferred destruction with Undo in the sheet. */
class OfflineRowActions(
    val retry: (owner: String) -> Unit,
    val cancel: (owner: String) -> Unit,
    val remove: (owner: String) -> Unit,
    val removeAll: () -> Unit,
)

/**
 * The Library's one sheet for offline Codices (with progress, Cancel, Retry, Remove) above exported
 * downloads. Opened for a Codex ([markedCodexId]), it scrolls to and marks that Codex's row. Leaving the
 * sheet commits any removal still waiting on Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTransfersSheet(
    offline: OfflineStorageUiState,
    offlineActions: OfflineRowActions,
    batches: List<PostDownloadBatch>,
    hiddenDownloads: Set<Long>,
    downloadActions: DownloadTileActions,
    markedCodexId: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        UndoLeaveEffect(LIBRARY_TRANSFERS_UNDO_SCOPE)
        val liveAnchor = LocalUndoSlot.current.offer.collectAsStateWithLifecycle().value?.anchor
            ?.takeIf { it.scope == LIBRARY_TRANSFERS_UNDO_SCOPE }
        val items = remember(batches) { batches.asReversed().flatMap { it.items } }
        val shownItems = items.filter { it.id !in hiddenDownloads || liveAnchor == downloadUndoAnchor(it.id) }
        val offlineUndo = liveAnchor?.takeIf { it.key == OfflineAllUndoAnchor.key || it.key in offline.hiddenOwners }
        val downloadsUndo = liveAnchor?.takeIf { it == DownloadsUndoAnchor }
        val marked = markedCodexId?.let(::codexOfflineOwner)
        val grid = rememberLazyGridState()
        val offlineRowsStart = 1 + listOfNotNull(offline.message, offlineUndo).size
        LaunchedEffect(marked) {
            val row = offline.owners.indexOfFirst { it.id == marked }
            if (row >= 0) grid.scrollToItem(offlineRowsStart + row)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp).testTag("library-transfers"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val showOffline = offline.owners.isNotEmpty() || offlineUndo != null || offline.message != null
            val showDownloads = shownItems.isNotEmpty() || downloadsUndo != null
            if (!showOffline && !showDownloads) fullLine("empty") { EmptyState("Nothing offline or downloaded") }
            if (showOffline) offlineSection(offline, offlineActions, offlineUndo, marked)
            if (showDownloads) {
                fullLine("downloads-header") { DownloadsHeader(batches, items.filter { it.id !in hiddenDownloads }, downloadActions) }
                downloadsUndo?.let { anchor -> fullLine("downloads-undo") { UndoRow(anchor) } }
                items(shownItems, key = { "download:${it.id}" }) { item ->
                    if (item.id in hiddenDownloads) {
                        UndoRow(downloadUndoAnchor(item.id))
                    } else {
                        DownloadItemCard(item, batches.firstOrNull { batch -> batch.items.any { it.id == item.id } }, downloadActions)
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.offlineSection(
    offline: OfflineStorageUiState,
    actions: OfflineRowActions,
    undo: UndoAnchor?,
    marked: String?,
) {
    fullLine("offline-header") { OfflineHeader(enabled = offline.owners.isNotEmpty(), onRemoveAll = actions.removeAll) }
    offline.message?.let { message ->
        fullLine("offline-message") { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
    undo?.let { anchor -> fullLine("offline-undo") { UndoRow(anchor) } }
    items(offline.owners, key = { "offline:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { owner ->
        OfflineOwnerRow(owner, offline.controlsEnabled, marked = owner.id == marked, actions)
    }
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
