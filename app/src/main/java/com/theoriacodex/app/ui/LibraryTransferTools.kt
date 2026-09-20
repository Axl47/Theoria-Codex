package com.theoriacodex.app.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.backup.AndroidBackupDocuments
import com.theoriacodex.app.backup.BackupControls
import com.theoriacodex.app.backup.BackupDialogs
import com.theoriacodex.app.backup.BackupViewModel
import com.theoriacodex.app.backup.ProfileBackupActions
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.media.DownloadJobsButton
import com.theoriacodex.app.media.DownloadJobsSheet
import com.theoriacodex.app.media.PostDownloadsViewModel
import com.theoriacodex.app.media.MediaCacheMaintenance
import com.theoriacodex.app.media.OfflineStorageViewModel
import com.theoriacodex.app.media.OfflineStorageControls
import com.theoriacodex.app.media.OfflineStorageSheet
import kotlinx.coroutines.flow.first

/** Activity-owned transfer work with shell-owned Android document pickers and transient dialogs. */
internal class LibraryTransferTools(
    val downloads: PostDownloadsViewModel,
    val backup: BackupViewModel?,
    val offline: OfflineStorageViewModel?,
    val chooseBackupDestination: () -> Unit,
    val chooseBackup: () -> Unit,
) {
    var showDownloads by mutableStateOf(false)
}

@Composable
internal fun rememberLibraryTransferTools(
    container: TheoriaAppContainer,
    onMessage: suspend (String) -> Unit,
): LibraryTransferTools {
    val context = LocalContext.current.applicationContext
    val downloads = viewModel<PostDownloadsViewModel>(
        key = "library-downloads",
        factory = viewModelFactory {
            initializer {
                PostDownloadsViewModel(
                    context = context,
                    readSettings = { container.data.settingsRepository.observeSettings().first().cache },
                    resolvePost = { post -> container.sources.registry.adapterFor(post.id.source)?.resolvePost(post.id) },
                    exportAnimation = { post ->
                        container.sources.pixivUgoiraClient.exportToMp4(context, post.id.sourcePostId, post.title).map { Unit }
                    },
                )
            }
        },
    )
    val backup = container.workflows.profileBackup?.let { service ->
        viewModel<BackupViewModel>(key = "profile-backup", factory = viewModelFactory {
            initializer { BackupViewModel(ProfileBackupActions(service), AndroidBackupDocuments(context.contentResolver)) }
        })
    }
    val offline = container.features.offlineMedia?.let { coordinator ->
        val maintenance = remember(container) {
            MediaCacheMaintenance(context, container.data.cacheRepository, container.sources.pixivUgoiraClient)
        }
        viewModel<OfflineStorageViewModel>(key = "offline-storage",
            factory = OfflineStorageViewModel.factory(container.data.codexRepository, coordinator, maintenance),
        )
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        backup?.export(uri?.toString())
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        backup?.import(uri?.toString())
    }
    val currentMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(backup) { backup?.effects?.collect { currentMessage(it) } }
    return remember(downloads, backup, offline, export, import) {
        LibraryTransferTools(downloads, backup, offline,
            chooseBackupDestination = { export.launch("theoria-backup.json") },
            chooseBackup = { import.launch(arrayOf("application/json", "application/octet-stream")) },
        )
    }
}

@Composable
internal fun libraryStorageSummary(tools: LibraryTransferTools): String? {
    val owner = tools.offline ?: return null
    val state by owner.state.collectAsStateWithLifecycle()
    return "${state.offline.postCount} offline posts · ${Formatter.formatShortFileSize(LocalContext.current, state.offline.bytes)}"
}

@Composable
internal fun LibraryTransferSettings(tools: LibraryTransferTools) {
    tools.offline?.let { owner ->
        val state by owner.state.collectAsStateWithLifecycle()
        OfflineStorageControls(state, owner::open)
    }
    val batches by tools.downloads.batches.collectAsStateWithLifecycle()
    DownloadJobsButton(batches = batches, onClick = { tools.showDownloads = true })
    tools.backup?.let { owner ->
        val state by owner.state.collectAsStateWithLifecycle()
        BackupControls(state, owner::showExportOptions, tools.chooseBackup)
    }
}

@Composable
internal fun LibraryTransferDialogs(tools: LibraryTransferTools) {
    tools.offline?.let { owner ->
        val state by owner.state.collectAsStateWithLifecycle()
        if (state.isOpen) OfflineStorageSheet(
            state, owner::dismiss, owner::retry, owner::cancel, owner::requestRemove,
            owner::requestClearOffline, owner::confirmRemoval, owner::dismissRemoval, owner::clearDisposableCache,
        )
    }
    if (tools.showDownloads) {
        val batches by tools.downloads.batches.collectAsStateWithLifecycle()
        DownloadJobsSheet(batches, tools.downloads::cancel, tools.downloads::retryFailed) { tools.showDownloads = false }
    }
    tools.backup?.let { owner ->
        val state by owner.state.collectAsStateWithLifecycle()
        BackupDialogs(state, owner::dismissDialog, owner::includeRecents, owner::applyPreferences,
            onExport = { owner.dismissDialog(); tools.chooseBackupDestination() }, onRestore = owner::restore,
        )
    }
}
