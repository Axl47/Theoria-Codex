package com.theoriacodex.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.backup.AutoBackupControls
import com.theoriacodex.app.backup.BackupControls
import com.theoriacodex.app.backup.autoBackupSummary
import com.theoriacodex.app.ui.LibraryTransferTools

/**
 * Builds Settings' Backup row and page from the activity's transfer tools: backing up goes straight to
 * the document picker, and a failed daily backup shows as the row's value.
 */
@Composable
internal fun rememberSettingsBackupEntry(tools: LibraryTransferTools): SettingsBackupEntry {
    val summary = tools.autoBackup?.let { owner ->
        val status by owner.status.collectAsStateWithLifecycle()
        autoBackupSummary(status)
    }
    return remember(tools, summary) { SettingsBackupEntry(summary) { BackupPageControls(tools) } }
}

@Composable
private fun BackupPageControls(tools: LibraryTransferTools) {
    val backupState by tools.backup.state.collectAsStateWithLifecycle()
    BackupControls(backupState, tools.backup::includeRecents, tools.chooseBackupDestination, tools.chooseBackup)
    tools.autoBackup?.let { owner ->
        val status by owner.status.collectAsStateWithLifecycle()
        AutoBackupControls(status, tools.chooseAutoBackupFolder, { owner.backUpNow() }, tools.turnOffAutoBackup)
    }
}
