package com.theoriacodex.app.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import java.util.Locale

@Composable
internal fun DownloadJobsButton(batches: List<PostDownloadBatch>, onClick: () -> Unit) {
    val activeCount = batches.sumOf { batch -> batch.items.count { it.active } }
    TextButton(onClick = onClick) { Text(if (activeCount > 0) "Downloads ($activeCount)" else "Downloads") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadJobsSheet(
    batches: List<PostDownloadBatch>,
    onCancel: (Long) -> Unit,
    onRetryFailed: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp)) {
            item {
                Text("Downloads", style = MaterialTheme.typography.titleLarge)
                Text("Keep the app open until all items are saved.", style = MaterialTheme.typography.bodyMedium)
            }
            if (batches.isEmpty()) item { Text("Saved files will appear here and in your device’s Downloads folder.") }
            batches.asReversed().forEach { batch ->
                item(key = "batch-${batch.id}") { DownloadBatchHeader(batch, onCancel, onRetryFailed) }
                items(batch.items, key = { "download-${it.id}" }) { DownloadItemRow(it) }
                item(key = "divider-${batch.id}") { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            }
        }
    }
}

@Composable
private fun DownloadBatchHeader(batch: PostDownloadBatch, onCancel: (Long) -> Unit, onRetryFailed: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(batch.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "${batch.savedCount} of ${batch.items.size} saved" + if (batch.failedCount > 0) " · ${batch.failedCount} failed" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (batch.active) LinearProgressIndicator(
            progress = { batch.settledCount.toFloat() / batch.items.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (batch.active) TextButton(onClick = { onCancel(batch.id) }) { Text("Cancel remaining") }
            if (batch.failedCount > 0) TextButton(onClick = { onRetryFailed(batch.id) }) { Text("Retry failed") }
        }
    }
}

@Composable
private fun DownloadItemRow(item: PostDownloadItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.request.post.title?.takeIf { it.isNotBlank() } ?: "Post ${item.request.post.id.sourcePostId}",
            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(item.request.post.id.source.displayName())
                item.request.selectedMedia?.takeIf { it.totalPages > 1 }?.let { append(" · Page ${it.pageIndex + 1}") }
                append(" · ${downloadItemStatusLabel(item)}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (item.status == PostDownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.status == PostDownloadStatus.DOWNLOADING) {
            val total = item.totalBytes
            if (total == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(
                progress = { (item.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    PostDownloadStatus.FAILED -> item.message ?: "Could not save this post. Try again."
    PostDownloadStatus.CANCELLED -> "Cancelled"
}

private fun downloadBytesLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
