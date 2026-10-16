package com.theoriacodex.app.media

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.util.Locale

@Composable
internal fun DownloadJobsButton(
    batches: List<PostDownloadBatch>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = batches.sumOf { it.items.size }
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(if (count > 0) "Downloads ($count)" else "Downloads")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadJobsSheet(
    batches: List<PostDownloadBatch>,
    onCancel: (Long) -> Unit,
    onRetryFailed: (Long) -> Unit,
    onOpen: (PostDownloadItem) -> Unit,
    onRemove: (Set<Long>, Boolean) -> Unit,
    localFile: suspend (PostDownloadItem) -> DownloadedPostFile?,
    onDismiss: () -> Unit,
) {
    val items = remember(batches) { batches.asReversed().flatMap { it.items } }
    var actionItemId by remember { mutableStateOf<Long?>(null) }
    var deleteIds by remember { mutableStateOf<Set<Long>?>(null) }
    val actionItem = items.firstOrNull { it.id == actionItemId }
    val actionBatch = batches.firstOrNull { batch -> batch.items.any { it.id == actionItemId } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DownloadJobsHeader(batches, items, onCancel, onRetryFailed, onRemove, onDelete = { deleteIds = it })
            Row(
                modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(items.size.toString(), modifier = Modifier.testTag("download-count"), style = MaterialTheme.typography.labelLarge)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("downloads-grid"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = PostDownloadItem::id) { item ->
                    DownloadItemCard(item, localFile, onClick = { onOpen(item) }, onLongClick = { actionItemId = item.id })
                }
            }
        }
    }
    if (actionItem != null) DownloadItemActions(
        actionItem, actionBatch, onCancel, onRetryFailed, onRemove,
        onDelete = { deleteIds = it }, onDismiss = { actionItemId = null },
    )
    deleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleteIds = null },
            title = { Text(if (ids.size == 1) "Delete downloaded file?" else "Delete ${ids.size} downloaded files?") },
            text = { Text("Delete these files from the device and remove their download records? Active downloads will be cancelled.") },
            confirmButton = { TextButton(onClick = { onRemove(ids, true); deleteIds = null }) { Text("Delete files") } },
            dismissButton = { TextButton(onClick = { deleteIds = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadJobsHeader(
    batches: List<PostDownloadBatch>, items: List<PostDownloadItem>,
    onCancel: (Long) -> Unit, onRetryFailed: (Long) -> Unit,
    onRemove: (Set<Long>, Boolean) -> Unit, onDelete: (Set<Long>) -> Unit,
) {
    var showBulkActions by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { showBulkActions = true }, enabled = items.isNotEmpty()) {
                Icon(Icons.Default.MoreVert, contentDescription = "Manage all downloads")
            }
            DropdownMenu(expanded = showBulkActions, onDismissRequest = { showBulkActions = false }) {
                DropdownMenuItem(text = { Text("Delete all files") }, onClick = {
                    onDelete(items.mapTo(mutableSetOf()) { it.id }); showBulkActions = false
                })
                DropdownMenuItem(text = { Text("Remove all from Downloads") }, onClick = {
                    onRemove(items.mapTo(mutableSetOf()) { it.id }, false); showBulkActions = false
                })
                if (batches.any { it.active }) DropdownMenuItem(text = { Text("Cancel all downloads") }, onClick = {
                    batches.filter { it.active }.forEach { onCancel(it.id) }; showBulkActions = false
                })
                if (batches.any { it.failedCount > 0 }) DropdownMenuItem(text = { Text("Retry all failed") }, onClick = {
                    batches.filter { it.failedCount > 0 }.forEach { onRetryFailed(it.id) }; showBulkActions = false
                })
            }
        }
    }
}

@Composable
private fun DownloadItemActions(
    actionItem: PostDownloadItem, actionBatch: PostDownloadBatch?,
    onCancel: (Long) -> Unit, onRetryFailed: (Long) -> Unit,
    onRemove: (Set<Long>, Boolean) -> Unit, onDelete: (Set<Long>) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Download actions") },
        text = {
            Column {
                TextButton(onClick = { onDelete(setOf(actionItem.id)); onDismiss() }) { Text("Delete file") }
                TextButton(onClick = { onRemove(setOf(actionItem.id), false); onDismiss() }) { Text("Remove from Downloads") }
                if (actionItem.active && actionBatch != null) TextButton(onClick = { onCancel(actionBatch.id); onDismiss() }) {
                    Text("Cancel batch")
                }
                if (actionItem.status == PostDownloadStatus.FAILED && actionBatch != null) TextButton(onClick = {
                    onRetryFailed(actionBatch.id); onDismiss()
                }) { Text("Retry failed") }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text("Close") } },
    )
}

@Composable
private fun DownloadItemCard(
    item: PostDownloadItem,
    localFile: suspend (PostDownloadItem) -> DownloadedPostFile?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("download:${item.id}")
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Manage download")) {
        DownloadItemPreview(item, localFile)
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.request.post.title?.takeIf(String::isNotBlank) ?: "Post ${item.request.post.id.sourcePostId}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.request.post.id.source.displayName()} · ${item.request.originLabel}",
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.request.selectedMedia?.takeIf { it.totalPages > 1 }?.let {
                Text("Page ${it.pageIndex + 1} of ${it.totalPages}", style = MaterialTheme.typography.labelSmall)
            }
            DownloadItemStatus(item)
        }
    }
}

@Composable
private fun DownloadItemPreview(item: PostDownloadItem, localFile: suspend (PostDownloadItem) -> DownloadedPostFile?) {
    val context = LocalContext.current
    val preview = item.request.post.preview
    val local by produceState<DownloadedPostFile?>(null, item.id, item.status) {
        if (preview.url == null && preview.localPath == null &&
            item.status in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS)) {
            value = runCatchingPreservingCancellation { localFile(item) }.getOrNull()
        }
    }
    val model = remember(item.request, local, context) {
        preview.localPath ?: preview.url?.let {
            MediaRequestFactory.imageRequest(context, it, item.request.post.id.source, crossfade = false, staticAnimatedWebPFrame = true)
        } ?: local?.uri
    }
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model != null) AsyncImage(model = model, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun DownloadItemStatus(item: PostDownloadItem) {
    if (item.message != null || item.status !in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS)) {
        Text(item.message ?: downloadItemStatusLabel(item), style = MaterialTheme.typography.bodySmall,
            color = if (item.status == PostDownloadStatus.FAILED || !item.active && item.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (item.active) {
        val total = item.totalBytes
        if (item.status == PostDownloadStatus.DOWNLOADING && total != null && total > 0) {
            LinearProgressIndicator(progress = { (item.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

internal fun downloadItemStatusLabel(item: PostDownloadItem): String = when (item.status) {
    PostDownloadStatus.QUEUED -> "Queued"
    PostDownloadStatus.PREPARING -> "Preparing media"
    PostDownloadStatus.DOWNLOADING -> item.totalBytes?.let {
        "${downloadBytesLabel(item.downloadedBytes)} of ${downloadBytesLabel(it)}"
    } ?: "${downloadBytesLabel(item.downloadedBytes)} downloaded"
    PostDownloadStatus.WAITING -> item.message ?: "Waiting for a connection"
    PostDownloadStatus.SAVED -> "Saved"
    PostDownloadStatus.ALREADY_EXISTS -> "File already exists; original kept"
    PostDownloadStatus.FAILED -> item.message ?: "Could not save this post. Try again."
    PostDownloadStatus.CANCELLED -> "Cancelled"
}

private fun downloadBytesLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
