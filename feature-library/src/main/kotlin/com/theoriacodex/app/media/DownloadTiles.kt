package com.theoriacodex.app.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.theoriacodex.app.ui.undo.UndoAnchor
import java.util.Locale

class DownloadTileActions(
    val open: (PostDownloadItem) -> Unit,
    val share: (PostDownloadItem) -> Unit,
    val cancel: (batchId: Long) -> Unit,
    val retryFailed: (batchId: Long) -> Unit,
    val remove: (ids: Set<Long>, deleteFiles: Boolean, anchor: UndoAnchor) -> Unit,
    val localFile: suspend (PostDownloadItem) -> DownloadedPostFile?,
)

internal fun downloadUndoAnchor(id: Long) = UndoAnchor(LIBRARY_TRANSFERS_UNDO_SCOPE, "download:$id")
internal val DownloadsUndoAnchor = UndoAnchor(LIBRARY_TRANSFERS_UNDO_SCOPE, "downloads")

@Composable
internal fun DownloadsHeader(batches: List<PostDownloadBatch>, items: List<PostDownloadItem>, actions: DownloadTileActions) {
    var expanded by remember { mutableStateOf(false) }
    val ids = items.mapTo(mutableSetOf()) { it.id }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(items.size.toString(), modifier = Modifier.testTag("download-count"), style = MaterialTheme.typography.labelLarge)
        Box {
            IconButton(onClick = { expanded = true }, enabled = items.isNotEmpty()) {
                Icon(Icons.Default.MoreVert, contentDescription = "Manage all downloads")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val close = { expanded = false }
                DropdownMenuItem(text = { Text("Delete all files") }, onClick = { close(); actions.remove(ids, true, DownloadsUndoAnchor) })
                DropdownMenuItem(text = { Text("Remove all from Downloads") }, onClick = { close(); actions.remove(ids, false, DownloadsUndoAnchor) })
                if (batches.any { it.active }) DropdownMenuItem(text = { Text("Cancel all downloads") }, onClick = {
                    close(); batches.filter { it.active }.forEach { actions.cancel(it.id) }
                })
                if (batches.any { it.retryable }) DropdownMenuItem(text = { Text("Retry all failed") }, onClick = {
                    close(); batches.filter { it.retryable }.forEach { actions.retryFailed(it.id) }
                })
            }
        }
    }
}

@Composable
internal fun DownloadItemCard(item: PostDownloadItem, batch: PostDownloadBatch?, actions: DownloadTileActions) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().testTag("download:${item.id}")
        .combinedClickable(onClick = { actions.open(item) }, onLongClick = { menu = true }, onLongClickLabel = "Manage download")) {
        DownloadItemPreview(item, actions.localFile)
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val source = item.request.post.id.source.displayName()
                val title = item.request.post.title?.takeIf(String::isNotBlank)
                Text(title ?: source, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(source.takeIf { title != null }, item.request.originLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.request.selectedMedia?.takeIf { it.totalPages > 1 }?.let {
                    Text("Page ${it.pageIndex + 1} of ${it.totalPages}", style = MaterialTheme.typography.labelSmall)
                }
                DownloadItemStatus(item)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Manage download") }
                DownloadItemMenu(menu, item, batch, actions) { menu = false }
            }
        }
    }
}

@Composable
private fun DownloadItemMenu(
    expanded: Boolean,
    item: PostDownloadItem,
    batch: PostDownloadBatch?,
    actions: DownloadTileActions,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (item.active && batch != null) {
            DropdownMenuItem(text = { Text("Cancel batch") }, onClick = { onDismiss(); actions.cancel(batch.id) })
        }
        if (item.status == PostDownloadStatus.SAVED || item.status == PostDownloadStatus.ALREADY_EXISTS) {
            DropdownMenuItem(text = { Text("Share") }, onClick = { onDismiss(); actions.share(item) })
        }
        if (item.retryable && batch != null) {
            DropdownMenuItem(text = { Text("Retry failed") }, onClick = { onDismiss(); actions.retryFailed(batch.id) })
        }
        DropdownMenuItem(text = { Text("Remove from Downloads") }, onClick = {
            onDismiss(); actions.remove(setOf(item.id), false, downloadUndoAnchor(item.id))
        })
        DropdownMenuItem(
            text = { Text("Delete file", color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); actions.remove(setOf(item.id), true, downloadUndoAnchor(item.id)) },
        )
    }
}

@Composable
private fun DownloadItemPreview(item: PostDownloadItem, localFile: suspend (PostDownloadItem) -> DownloadedPostFile?) {
    val context = LocalContext.current
    val preview = item.request.post.preview
    val local by produceState<Any?>(null, item.id, item.status) {
        if (preview.url == null && preview.localPath == null &&
            item.status in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS)) {
            value = runCatchingPreservingCancellation {
                localFile(item)?.let { downloadedPostPreview(context, it) }
            }.getOrNull()
        }
    }
    val model = remember(item.request, local, context) {
        preview.localPath ?: preview.url?.let {
            MediaRequestFactory.imageRequest(context, it, item.request.post.id.source, crossfade = false, staticAnimatedWebPFrame = true)
        } ?: local
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
        val encoded = item.encodedPercent
        if (item.status == PostDownloadStatus.ENCODING && encoded != null) {
            LinearProgressIndicator(progress = { (encoded / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else if (item.status == PostDownloadStatus.DOWNLOADING && total != null && total > 0) {
            LinearProgressIndicator(progress = { (item.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

internal fun downloadItemStatusLabel(item: PostDownloadItem): String = when (item.status) {
    PostDownloadStatus.QUEUED -> "Queued"
    PostDownloadStatus.PREPARING -> "Preparing media"
    PostDownloadStatus.ENCODING -> "Clipping"
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
