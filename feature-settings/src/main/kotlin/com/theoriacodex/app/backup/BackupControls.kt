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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.repository.ProfileBackupPreview
import com.theoriacodex.data.repository.BackupPreview
import com.theoriacodex.domain.series.languageName

/**
 * Back up and Restore. Backing up opens a sheet with the backup's choices; Restore also reads Mihon and Aniyomi
 * backups (`.tachibk`), which the page says because nothing else would tell the user.
 */
@Composable
fun BackupControls(state: BackupUiState, onIncludeRecents: (Boolean) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { choosing = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text(BACK_UP_LABEL)
        }
        OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.weight(1f)) {
            Text("Restore backup")
        }
    }
    Text(
        "Also restores Mihon and Aniyomi backups.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    if (choosing) {
        BackupSheet(state.includeRecents, onIncludeRecents, onDismiss = { choosing = false }) {
            choosing = false
            onExport()
        }
    }
}

/** What a backup holds, then the button that picks where to save it. */
@Composable
private fun BackupSheet(includeRecents: Boolean, onIncludeRecents: (Boolean) -> Unit, onDismiss: () -> Unit, onBackUp: () -> Unit) {
    ExpandedBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(BACK_UP_LABEL, style = MaterialTheme.typography.titleLarge)
            SwitchRow(
                title = "Include History and statistics",
                checked = includeRecents,
                contentPadding = PaddingValues(0.dp),
                onCheckedChange = onIncludeRecents,
            )
            Text(
                "Logins and downloaded media are not included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBackUp, modifier = Modifier.fillMaxWidth()) { Text("Back up") }
        }
    }
}

private const val BACK_UP_LABEL = "Back up profiles"

/** Daily backup to a folder, under its own "Daily backup" heading; compact because it is checked rarely and only needs attention on failure. */
@Composable
fun AutoBackupControls(
    status: AutoBackupStatus,
    onChooseFolder: () -> Unit,
    onBackUpNow: () -> Unit,
    onTurnOff: () -> Unit,
) {
    if (!status.enabled) {
        OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Choose folder")
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
        is BackupPreview.Series -> ImportSeriesDialog(preview, state.busy, onDismiss, onRestore)
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
                preview.newRepos.reposNotice()?.let { Text(it) }
                BackupCheckbox("Also apply global preferences", state.applyPreferences, onApplyPreferences, !state.busy)
                if (state.busy) Text("Restoring backup…")
            }
        },
        confirmButton = { TextButton(onClick = onRestore, enabled = !state.busy) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") } },
    )
}

/** A Mihon-family backup brings only series and its extension repos, so its preview says what of them comes along and which extensions are still missing. */
@Composable
private fun ImportSeriesDialog(import: BackupPreview.Series, busy: Boolean, onDismiss: () -> Unit, onImport: () -> Unit) {
    val preview = import.preview
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
                import.newRepos.reposNotice()?.let { Text(it) }
                // Ones to turn on first, so a count of "other" missing extensions reads against them.
                import.turnOnNotice()?.let { Text(it) }
                import.missingNotice()?.let { Text(it) }
                if (busy) Text("Importing…")
            }
        },
        confirmButton = { TextButton(onClick = onImport, enabled = !busy) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** "Also adds these extension repos: Keiyoushi, Kohi den." for the [ProfileBackupPreview.newRepos] or [BackupPreview.Series.newRepos] of a backup, so the user sees whose extensions they will be offered; null when none are new. */
internal fun List<String>.reposNotice(): String? = when (size) {
    0 -> null
    1 -> "Also adds this extension repo: ${single()}."
    else -> "Also adds these extension repos: ${joinToString()}."
}

/** "Needs these extensions: Comick, Gogoanime and 1 other.", naming what the backup names; null when nothing is missing. */
internal fun BackupPreview.Series.missingNotice(): String? {
    val named = missingSources
    if (named.isEmpty()) {
        if (unnamedMissingSources == 0) return null
        // Turning on the extensions named beside it would not cover these, so they are counted apart.
        return if (turnOn.isEmpty()) "A series stays unavailable until its extension is on here." else "Needs ${countLabel(unnamedMissingSources, "other extension")}."
    }
    val others = if (unnamedMissingSources > 0) " and ${countLabel(unnamedMissingSources, "other")}" else ""
    val subject = if (named.size + unnamedMissingSources == 1) "this extension" else "these extensions"
    return "Needs $subject: ${named.joinToString()}$others."
}

/** "Turn on in Extensions: Comix, Comix (Spanish).": installed extensions, and the language where only that is off; null when none. */
internal fun BackupPreview.Series.turnOnNotice(): String? = turnOn.takeIf { it.isNotEmpty() }?.joinToString(
    prefix = "Turn on in Extensions: ", postfix = ".",
) { off -> off.language?.let { "${off.extension} (${languageName(it)})" } ?: off.extension }

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
