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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.backup.AndroidBackupDocuments
import com.theoriacodex.app.backup.AutoBackupControls
import com.theoriacodex.app.backup.AutoBackupWorkflow
import com.theoriacodex.app.backup.adoptAutoBackupFolder
import com.theoriacodex.app.backup.releaseAutoBackupFolder
import com.theoriacodex.app.backup.BackupControls
import com.theoriacodex.app.backup.BackupDialogs
import com.theoriacodex.app.backup.BackupViewModel
import com.theoriacodex.app.backup.ProfileBackupActions
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.media.PostDownloadsViewModel
import com.theoriacodex.app.media.MediaCacheMaintenance
import com.theoriacodex.app.media.OfflineStorageViewModel
import com.theoriacodex.app.media.OfflineStorageSummary
import com.theoriacodex.app.media.DownloadRemovals
import com.theoriacodex.app.media.StorageSheet
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.media.totalBytes
import com.theoriacodex.app.media.PostDownloadItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Activity-owned transfer work with shell-owned Android document pickers and transient dialogs. */
internal class LibraryTransferTools(
    val downloads: PostDownloadsViewModel,
    val downloadRemovals: DownloadRemovals,
    val backup: BackupViewModel,
    val offline: OfflineStorageViewModel,
    val autoBackup: AutoBackupWorkflow?,
    val chooseBackupDestination: () -> Unit,
    val chooseBackup: () -> Unit,
    val chooseAutoBackupFolder: () -> Unit,
    val turnOffAutoBackup: () -> Unit,
) {
    /** Whether the Library's offline and downloads sheet is open. */
    var showDownloads by mutableStateOf(false)

    /** The Codex whose offline row the open sheet scrolls to and marks, if it was opened for one. */
    var markedCodexId by mutableStateOf<String?>(null)

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
    val downloads = viewModel(key = "library-downloads") {
        PostDownloadsViewModel(
            context = context,
            readSettings = { container.data.settingsRepository.observeSettings().first().cache },
            resolvePost = { post -> container.sources.registry.adapterFor(post.id.source)?.resolvePost(post.id) },
            exportAnimation = { post, marker ->
                container.sources.pixivUgoiraClient.exportToMp4(context, post.id.sourcePostId, post.title, marker).map { }
            },
        )
    }
    val backup = viewModel(key = "profile-backup") {
        BackupViewModel(ProfileBackupActions(container.workflows.profileBackup), AndroidBackupDocuments(context.contentResolver))
    }
    val maintenance = remember(container) {
        MediaCacheMaintenance(context, container.data.cacheRepository, container.sources.pixivUgoiraClient)
    }
    val offline = viewModel<OfflineStorageViewModel>(key = "offline-storage",
        factory = OfflineStorageViewModel.factory(container.data.codexRepository, container.features.offlineMedia, maintenance),
    )
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        backup.export(uri?.toString())
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        backup.import(uri?.toString())
    }
    val autoBackup = container.workflows.autoBackup
    val autoBackupFolder = rememberAutoBackupFolderPicker(autoBackup)
    val currentMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(backup) { backup.effects.collect { currentMessage(it) } }
    AutoBackupFailureEffect(autoBackup) { currentMessage(it) }
    return remember(downloads, backup, offline, export, import, autoBackup, autoBackupFolder) {
        LibraryTransferTools(downloads, DownloadRemovals(downloads::remove, downloads.viewModelScope), backup, offline, autoBackup,
            chooseBackupDestination = { export.launch("theoria-backup.json") },
            chooseBackup = { import.launch(arrayOf("application/json", "application/octet-stream")) },
            chooseAutoBackupFolder = { autoBackupFolder.launch(null) },
            turnOffAutoBackup = {
                context.contentResolver.releaseAutoBackupFolder(autoBackup?.status?.value?.folderUri)
                autoBackup?.disable()
            },
        )
    }
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
            if (failure != null) currentMessage("Automatic backup failed. See Settings > Storage & app.")
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
    val backupState by tools.backup.state.collectAsStateWithLifecycle()
    BackupControls(backupState, tools.backup::showExportOptions, tools.chooseBackup)
    tools.autoBackup?.let { owner ->
        val status by owner.status.collectAsStateWithLifecycle()
        AutoBackupControls(status, tools.chooseAutoBackupFolder, { owner.backUpNow() }, tools.turnOffAutoBackup)
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
    BackupDialogs(backupState, backup::dismissDialog, backup::includeRecents, backup::applyPreferences,
        onExport = { backup.dismissDialog(); tools.chooseBackupDestination() }, onRestore = backup::restore,
    )
}
