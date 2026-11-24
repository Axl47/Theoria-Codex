package com.theoriacodex.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.seriesIdOfOfflineOwner

/**
 * One offline owner in the Library's sheet: name, what is kept, its job's progress and any message about
 * it. Cancel or Retry sits on the row; removal is on the row's own menu. The details open the Codex or series.
 * [marked] highlights the Codex the sheet was opened for.
 */
@Composable
internal fun OfflineOwnerRow(owner: OfflineStorageOwner, enabled: Boolean, marked: Boolean, actions: OfflineRowActions) {
    val background = if (marked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        modifier = Modifier.fillMaxWidth().testTag("offline-owner:${owner.id}")
            .background(background, MaterialTheme.shapes.medium).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val opens = offlineOwnerOpener(owner.id, actions)?.let { Modifier.clickable(onClickLabel = "Open", onClick = it) } ?: Modifier
        Column(Modifier.weight(1f).then(opens), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(owner.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(offlineOwnerStatus(owner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfflineOwnerProgress(owner)
            owner.job?.failureMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        OfflineOwnerPrimaryAction(owner, enabled, actions)
        OfflineOwnerMenu(owner, enabled, actions)
    }
}

@Composable
private fun OfflineOwnerProgress(owner: OfflineStorageOwner) {
    val job = owner.job
    when {
        job?.isRunning == true -> LinearProgressIndicator(
            progress = { (job.completedPosts + job.failedPosts.size).toFloat() / job.totalPosts.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        owner.preparing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OfflineOwnerPrimaryAction(owner: OfflineStorageOwner, enabled: Boolean, actions: OfflineRowActions) {
    val job = owner.job
    when {
        owner.preparing || job?.isRunning == true ->
            TextButton(onClick = { actions.cancel(owner.id) }, enabled = enabled) { Text("Cancel") }
        job?.let { it.isCancelled || it.failureMessage != null } == true ->
            TextButton(onClick = { actions.retry(owner.id) }, enabled = enabled) { Text("Retry") }
    }
}

@Composable
private fun OfflineOwnerMenu(owner: OfflineStorageOwner, enabled: Boolean, actions: OfflineRowActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = "Manage ${owner.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Remove offline copies") }, onClick = { expanded = false; actions.remove(owner.id) })
        }
    }
}

/** Opens the Codex or series [owner] keeps offline; null for an owner with no page. */
private fun offlineOwnerOpener(owner: String, actions: OfflineRowActions): (() -> Unit)? =
    codexIdOfOfflineOwner(owner)?.let { id -> { actions.openCodex(id) } }
        ?: seriesIdOfOfflineOwner(owner)?.let { id -> { actions.openSeries(id) } }

/** What the row says is kept: posts (a series' chapters) available, or the running job's progress. */
internal fun offlineOwnerStatus(owner: OfflineStorageOwner): String {
    val job = owner.job
    val unit = if (seriesIdOfOfflineOwner(owner.id) != null) "chapter" else "post"
    val kept = if (owner.availablePosts == 1) "1 $unit offline" else "${owner.availablePosts} ${unit}s offline"
    return when {
        owner.preparing -> "Loading Codex"
        job?.isRunning == true -> "Saving ${job.completedPosts} of ${job.totalPosts}"
        job?.isCancelled == true -> "Cancelled · $kept"
        else -> kept
    }
}
