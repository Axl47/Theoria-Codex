package com.theoriacodex.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.backup.AutoBackupControls
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.app.backup.BackupControls
import com.theoriacodex.app.backup.autoBackupSummary
import com.theoriacodex.app.ui.LibraryTransferTools

/**
 * Builds Settings' Backup row and page from the activity's transfer tools: backing up asks its choices in a sheet,
 * then opens the document picker; daily backup is a group of its own, and its failure shows as the row's value.
 */
@Composable
internal fun rememberSettingsBackupEntry(tools: LibraryTransferTools): SettingsBackupEntry {
    val summary = tools.autoBackup?.let { owner ->
        val status by owner.status.collectAsStateWithLifecycle()
        autoBackupSummary(status)
    }
    return remember(tools, summary) {
        SettingsBackupEntry(summary, daily = tools.autoBackup?.let { owner -> { DailyBackupControls(owner, tools) } }) {
            val backupState by tools.backup.state.collectAsStateWithLifecycle()
            BackupControls(backupState, tools.backup::includeRecents, tools.chooseBackupDestination, tools.chooseBackup)
        }
    }
}

@Composable
private fun DailyBackupControls(owner: AutoBackupWorkflow, tools: LibraryTransferTools) {
    val status by owner.status.collectAsStateWithLifecycle()
    AutoBackupControls(status, tools.chooseAutoBackupFolder, { owner.backUpNow() }, tools.turnOffAutoBackup)
}
