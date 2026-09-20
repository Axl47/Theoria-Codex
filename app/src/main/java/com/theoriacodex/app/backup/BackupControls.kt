package com.theoriacodex.app.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.theoriacodex.data.repository.ProfileBackupPreview

@Composable
internal fun BackupControls(state: BackupUiState, onExport: () -> Unit, onImport: () -> Unit) {
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

@Composable
internal fun BackupDialogs(
    state: BackupUiState,
    onDismiss: () -> Unit,
    onIncludeRecents: (Boolean) -> Unit,
    onApplyPreferences: (Boolean) -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
) {
    if (state.showExportOptions) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Back up profiles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Save all profiles, collections, Likes, rules, saved searches, follows, and preferences.")
                    Text("Login credentials and downloaded media are not included.")
                    BackupCheckbox("Include Recents and reading positions", state.includeRecents, onIncludeRecents)
                }
            },
            confirmButton = { TextButton(onClick = onExport) { Text("Choose destination") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
    state.preview?.let { preview ->
        RestoreBackupDialog(preview, state, onDismiss, onApplyPreferences, onRestore)
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
                Text("${preview.profileNames.size} profiles · ${preview.collectionCount} collections · ${preview.savedPostCount} saved posts")
                Text(preview.profileNames.joinToString())
                Text("${preview.likeCount} Likes · ${preview.savedSearchCount} saved searches · ${preview.followedCreatorCount} follows · ${preview.recentCount} recent entries")
                Text("Adds new profiles and collections alongside your existing data. Your current profile stays selected.")
                Text("Login credentials and downloaded media are not included.")
                BackupCheckbox("Also apply global preferences", state.applyPreferences, onApplyPreferences, !state.busy)
                if (state.busy) Text("Restoring backup…")
            }
        },
        confirmButton = { TextButton(onClick = onRestore, enabled = !state.busy) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") } },
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
