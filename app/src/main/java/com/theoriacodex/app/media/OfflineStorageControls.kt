package com.theoriacodex.app.media

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun OfflineStorageControls(state: OfflineStorageUiState, onManage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OfflineStorageSummary(state)
        TextButton(onClick = onManage) { Text("Manage storage") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineStorageSheet(
    state: OfflineStorageUiState,
    onDismiss: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearOffline: () -> Unit,
    onConfirmRemoval: () -> Unit,
    onDismissRemoval: () -> Unit,
    onClearDisposable: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Storage", style = MaterialTheme.typography.titleLarge)
                OfflineStorageSummary(state)
                if (state.refreshing || state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                DisposableCacheControls(state, onClearDisposable)
                HorizontalDivider()
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Offline copies", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = onClearOffline,
                        enabled = state.controlsEnabled && state.owners.isNotEmpty(),
                    ) { Text("Remove all") }
                }
                if (state.owners.isEmpty()) Text("No offline copies", style = MaterialTheme.typography.bodyMedium)
            }
            items(state.owners, key = OfflineStorageOwner::id) { owner ->
                OfflineStorageOwnerRow(owner, state.controlsEnabled, onRetry, onCancel, onRemove)
            }
        }
    }
    state.pendingRemoval?.let { target ->
        OfflineRemovalDialog(target, state.controlsEnabled, onConfirmRemoval, onDismissRemoval)
    }
}

@Composable
private fun OfflineStorageSummary(state: OfflineStorageUiState) {
    val size = Formatter.formatShortFileSize(LocalContext.current, state.offline.bytes)
    Text("Offline: $size · ${state.offline.postCount} posts · ${state.offline.mediaCount} media files")
}

@Composable
private fun DisposableCacheControls(state: OfflineStorageUiState, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Disposable cache", style = MaterialTheme.typography.titleMedium)
        state.disposable?.let { cache ->
            val context = LocalContext.current
            Text(Formatter.formatShortFileSize(context, cache.totalBytes))
            Text(
                "Images ${Formatter.formatShortFileSize(context, cache.imageBytes)} · " +
                    "Video ${Formatter.formatShortFileSize(context, cache.videoBytes)} · " +
                    "GIF ${Formatter.formatShortFileSize(context, cache.gifBytes)} · " +
                    "Animation ${Formatter.formatShortFileSize(context, cache.animationBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onClear, enabled = state.controlsEnabled) { Text("Clear disposable cache") }
    }
}

@Composable
private fun OfflineStorageOwnerRow(
    owner: OfflineStorageOwner,
    enabled: Boolean,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("offline-owner:${owner.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(owner.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${owner.availablePosts} posts available offline", style = MaterialTheme.typography.bodyMedium)
        if (owner.preparing) {
            Text("Loading collection")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        owner.job?.let { job ->
            val status = if (job.isCancelled) "Cancelled · " else ""
            Text("$status${job.completedPosts} of ${job.totalPosts} posts saved")
            if (job.isRunning) LinearProgressIndicator(
                progress = { (job.completedPosts + job.failedPosts.size).toFloat() / job.totalPosts.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            job.failureMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (owner.preparing || owner.job?.isRunning == true) {
                TextButton(onClick = { onCancel(owner.id) }, enabled = enabled) { Text("Cancel") }
            } else if (owner.job?.let { it.isCancelled || it.failedPosts.isNotEmpty() } == true) {
                TextButton(onClick = { onRetry(owner.id) }, enabled = enabled) { Text("Retry") }
            }
            TextButton(onClick = { onRemove(owner.id) }, enabled = enabled) { Text("Remove") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun OfflineRemovalDialog(
    target: OfflineRemovalTarget,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target is OfflineRemovalTarget.All) "Remove all offline copies?" else "Remove offline copies?") },
        text = { Text(when (target) {
            is OfflineRemovalTarget.Owner ->
                "Remove offline copies for ${target.name}? Posts stay in your collections. Copies used by another collection remain."
            OfflineRemovalTarget.All -> "Remove all offline media from this device? Your collections stay saved."
        }) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.testTag("confirm-offline-removal")) {
                Text("Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
