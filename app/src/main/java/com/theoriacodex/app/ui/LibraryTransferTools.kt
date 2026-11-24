package com.theoriacodex.app.ui

import android.text.format.Formatter
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.backup.AndroidBackupDocuments
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.app.backup.adoptAutoBackupFolder
import com.theoriacodex.app.backup.releaseAutoBackupFolder
import com.theoriacodex.app.backup.BackupDialogs
import com.theoriacodex.app.backup.BackupViewModel
import com.theoriacodex.app.backup.ProfileBackupActions
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.media.PostDownloads
import com.theoriacodex.app.media.MediaCacheMaintenance
import com.theoriacodex.app.media.OfflineStorageViewModel
import com.theoriacodex.app.media.OfflineStorageSummary
import com.theoriacodex.app.media.DownloadRemovals
import com.theoriacodex.app.media.StorageSheet
import androidx.compose.foundation.layout.fillMaxWidth
import com.theoriacodex.app.media.totalBytes
import com.theoriacodex.app.media.PostDownloadItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Transfer work (the app's one download queue, Activity-owned removals, backup and offline owners) with shell-owned
 * Android document pickers and transient dialogs.
 */
internal class LibraryTransferTools(
    val downloads: PostDownloads,
    val downloadRemovals: DownloadRemovals,
    val backup: BackupViewModel,
    val offline: OfflineStorageViewModel,
    val autoBackup: AutoBackupWorkflow?,
    val chooseBackupDestination: () -> Unit,
    val chooseBackup: () -> Unit,
    val chooseAutoBackupFolder: () -> Unit,
    val turnOffAutoBackup: () -> Unit,
    sheetOpen: MutableState<Boolean>,
    sheetCodexId: MutableState<String?>,
) {
    /** Whether the Library's offline and downloads sheet is open; saved, so a rotation keeps it open. */
    var showDownloads by sheetOpen

    /** The Codex whose offline row the open sheet scrolls to and marks, if it was opened for one. */
    var markedCodexId by sheetCodexId

    /** Opens the offline and downloads sheet, on [codexId]'s row when one is given. */
    fun openTransfers(codexId: String?) {
        markedCodexId = codexId
        showDownloads = true
    }
}

@Composable
internal fun rememberLibraryTransferTools(
    container: TheoriaAppContainer,
    onMessage: suspend (String) -> Unit,
): LibraryTransferTools {
    val context = LocalContext.current.applicationContext
    val downloads = container.features.postDownloads
    val backup = viewModel(key = "profile-backup") {
        BackupViewModel(ProfileBackupActions(container.workflows.profileBackup), AndroidBackupDocuments(context.contentResolver))
    }
    val maintenance = remember(container) {
        MediaCacheMaintenance(context, container.data.cacheRepository, container.sources.pixivUgoiraClient)
    }
    val offline = viewModel<OfflineStorageViewModel>(key = "offline-storage",
        factory = OfflineStorageViewModel.factory(
            container.data.codexRepository, container.features.offlineMedia, maintenance, container.applicationScope,
            container.series?.repository, container.series?.downloads,
        ),
    )
    val downloadRemovals = viewModel(key = "download-removals") { DownloadRemovals(downloads::removeNow, container.applicationScope) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        backup.export(uri?.toString())
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        backup.import(uri?.toString())
    }
    val autoBackup = container.workflows.autoBackup
    val autoBackupFolder = rememberAutoBackupFolderPicker(autoBackup)
    val sheetOpen = rememberSaveable { mutableStateOf(false) }
    val sheetCodexId = rememberSaveable { mutableStateOf<String?>(null) }
    val currentMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(backup) { backup.effects.collect { currentMessage(it) } }
    AutoBackupFailureEffect(autoBackup) { currentMessage(it) }
    val tools = remember(downloads, downloadRemovals, backup, offline, export, import, autoBackup, autoBackupFolder) {
        LibraryTransferTools(downloads, downloadRemovals, backup, offline, autoBackup,
            chooseBackupDestination = { export.launch("theoria-backup.json") },
            chooseBackup = { import.launch(arrayOf("*/*")) },
            chooseAutoBackupFolder = { autoBackupFolder.launch(null) },
            turnOffAutoBackup = {
                context.contentResolver.releaseAutoBackupFolder(autoBackup?.status?.value?.folderUri)
                autoBackup?.disable()
            },
            sheetOpen = sheetOpen,
            sheetCodexId = sheetCodexId,
        )
    }
    // The open sheet shows the reason itself; closed, it would otherwise be lost.
    LaunchedEffect(tools) { offline.preparationFailures.collect { if (!tools.showDownloads) currentMessage(it) } }
    return tools
}

@Composable
private fun rememberAutoBackupFolderPicker(autoBackup: AutoBackupWorkflow?): ManagedActivityResultLauncher<Uri?, Uri?> {
    val resolver = LocalContext.current.applicationContext.contentResolver
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && autoBackup != null) {
            val previous = autoBackup.status.value.folderUri
            val name = resolver.adoptAutoBackupFolder(tree)
            if (previous != tree.toString()) resolver.releaseAutoBackupFolder(previous)
            autoBackup.enable(tree.toString(), name)
        }
    }
}

/** A backup that keeps failing protects nothing, so say so where it will be seen, not only in Settings. */
@Composable
private fun AutoBackupFailureEffect(autoBackup: AutoBackupWorkflow?, onMessage: suspend (String) -> Unit) {
    val currentMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(autoBackup) {
        autoBackup?.status?.map { it.failure }?.distinctUntilChanged()?.collect { failure ->
            if (failure != null) currentMessage("Automatic backup failed. See Settings > Backup.")
        }
    }
}

@Composable
internal fun libraryStorageSummary(tools: LibraryTransferTools): String {
    val owner = tools.offline
    LaunchedEffect(owner) { owner.refreshUsage() }
    val state by owner.state.collectAsStateWithLifecycle()
    return "${Formatter.formatShortFileSize(LocalContext.current, state.totalBytes())} used"
}

@Composable
internal fun LibraryTransferSettings(tools: LibraryTransferTools) {
    val offlineState by tools.offline.state.collectAsStateWithLifecycle()
    OfflineStorageSummary(offlineState)
    OutlinedButton(onClick = tools.offline::open, modifier = Modifier.fillMaxWidth()) {
        Text("Manage storage")
    }
}

@Composable
internal fun LibraryTransferDialogs(tools: LibraryTransferTools, onOpenPost: suspend (PostDownloadItem) -> Unit) {
    val offline = tools.offline
    val offlineState by offline.state.collectAsStateWithLifecycle()
    if (offlineState.isOpen) StorageSheet(offlineState, offline::dismiss, offline::refreshUsage, offline::clearDisposableCache)
    if (tools.showDownloads) LibraryTransfersSheetHost(tools, onOpenPost)
    val backup = tools.backup
    val backupState by backup.state.collectAsStateWithLifecycle()
    BackupDialogs(backupState, backup::dismissDialog, backup::applyPreferences, backup::restore)
}
