package com.theoriacodex.app.backup

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.repository.ProfileBackupPreview
import com.theoriacodex.data.repository.SeriesImportPreview
import com.theoriacodex.data.repository.BackupPreview

@Composable
fun BackupControls(state: BackupUiState, onIncludeRecents: (Boolean) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    SwitchRow(
        title = "Include History and statistics",
        checked = state.includeRecents,
        enabled = !state.busy,
        contentPadding = PaddingValues(0.dp),
        onCheckedChange = onIncludeRecents,
    )
    Text(
        "Logins and downloaded media are not included.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onExport, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text("Back up profiles")
        }
        OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text("Restore backup")
        }
    }
    state.operation?.let { operation ->
        Text(
            text = when (operation) {
                BackupOperation.EXPORT -> "Saving backup…"
                BackupOperation.READ -> "Checking backup…"
                BackupOperation.RESTORE -> "Restoring backup…"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Daily backup to a folder; compact because it is checked rarely and only needs attention on failure. */
@Composable
fun AutoBackupControls(
    status: AutoBackupStatus,
    onChooseFolder: () -> Unit,
    onBackUpNow: () -> Unit,
    onTurnOff: () -> Unit,
) {
    if (!status.enabled) {
        OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Back up daily to a folder")
        }
        return
    }
    Text(
        text = "Daily backup to ${status.folderName ?: "your folder"} · ${autoBackupProgress(status)}",
        style = MaterialTheme.typography.bodySmall,
    )
    status.failure?.let { failure ->
        Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBackUpNow, enabled = !status.running) { Text("Back up now") }
        TextButton(onClick = onChooseFolder) { Text("Change folder") }
        TextButton(onClick = onTurnOff) { Text("Turn off") }
    }
}

private fun autoBackupProgress(status: AutoBackupStatus): String {
    val last = status.lastSuccessEpochMs
    return when {
        status.running -> "Saving…"
        last == null -> "No backup yet"
        System.currentTimeMillis() - last < DateUtils.MINUTE_IN_MILLIS -> "Last just now"
        else -> "Last ${DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
    }
}

/** What the Settings home row says about daily backup; [failed] when the last automatic backup failed. */
data class AutoBackupSummary(val text: String, val failed: Boolean = false)

/** The Settings home row's value: a failure where it will be seen, else how daily backup stands. */
fun autoBackupSummary(status: AutoBackupStatus): AutoBackupSummary = when {
    status.failure != null -> AutoBackupSummary(status.failure, failed = true)
    !status.enabled -> AutoBackupSummary("Daily backup off")
    else -> AutoBackupSummary("Daily · ${autoBackupProgress(status)}")
}

/** The restore preview, the one backup step that asks first: restoring adds to every matching profile. */
@Composable
fun BackupDialogs(
    state: BackupUiState,
    onDismiss: () -> Unit,
    onApplyPreferences: (Boolean) -> Unit,
    onRestore: () -> Unit,
) {
    when (val preview = state.preview) {
        is BackupPreview.Profiles -> RestoreBackupDialog(preview.preview, state, onDismiss, onApplyPreferences, onRestore)
        is BackupPreview.Series -> ImportSeriesDialog(preview.preview, state.busy, onDismiss, onRestore)
        null -> Unit
    }
}

@Composable
private fun RestoreBackupDialog(
    preview: ProfileBackupPreview,
    state: BackupUiState,
    onDismiss: () -> Unit,
    onApplyPreferences: (Boolean) -> Unit,
    onRestore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Restore backup") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    listOf(
                        countLabel(preview.profileNames.size, "profile"),
                        countLabel(preview.collectionCount, "Codex", "Codices"),
                        countLabel(preview.savedPostCount, "saved post"),
                    ).joinToString(" · "),
                )
                Text(preview.profileNames.joinToString())
                Text("${preview.likeCount} Likes · ${preview.savedSearchCount} saved searches · ${preview.followedCreatorCount} follows · ${preview.recentCount} recent entries" +
                    if (preview.seriesCount > 0) " · ${preview.seriesCount} series" else "")
                Text("Adds what's missing to matching profiles and Codices. Nothing you have is removed or changed, and restoring twice adds nothing more.")
                Text("Login credentials and downloaded media are not included.")
                BackupCheckbox("Also apply global preferences", state.applyPreferences, onApplyPreferences, !state.busy)
                if (state.busy) Text("Restoring backup…")
            }
        },
        confirmButton = { TextButton(onClick = onRestore, enabled = !state.busy) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") } },
    )
}

/** A Mihon-family backup brings only series, so its preview says what of them comes along and nothing else. */
@Composable
private fun ImportSeriesDialog(preview: SeriesImportPreview, busy: Boolean, onDismiss: () -> Unit, onImport: () -> Unit) {
    val counts = listOfNotNull(
        preview.summary(),
        countLabel(preview.chaptersRead, "chapter read", "chapters read").takeIf { preview.chaptersRead > 0 },
        countLabel(preview.episodesWatched, "episode watched", "episodes watched").takeIf { preview.episodesWatched > 0 },
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import series") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(counts.joinToString(" · "))
                Text("Adds them to Series with their progress. Nothing you have is removed, and importing twice adds nothing more.")
                Text("A series stays unavailable until its extension is on here.")
                if (busy) Text("Importing…")
            }
        },
        confirmButton = { TextButton(onClick = onImport, enabled = !busy) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun BackupCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
