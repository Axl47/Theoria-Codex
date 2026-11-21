package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.media.CodexOfflineControls
import com.theoriacodex.app.media.DownloadTileActions
import com.theoriacodex.app.media.LibraryTransfersButton
import com.theoriacodex.app.media.LibraryTransfersSheet
import com.theoriacodex.app.media.LocalCodexOfflineControls
import com.theoriacodex.app.media.LocalCodexOfflineStates
import com.theoriacodex.app.media.OfflineRowActions
import com.theoriacodex.app.media.PostDownloadItem
import com.theoriacodex.app.media.codexOfflineOwner
import com.theoriacodex.app.media.libraryTransfersStatus
import com.theoriacodex.app.media.openDownloadedPostFile
import com.theoriacodex.app.media.openPostDownload
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoAnchor
import kotlinx.coroutines.launch

/**
 * The offline ring's state for every Codex kept offline and what the ring and the Codex menu can do to a copy,
 * provided over the whole app so Codex tiles, detail screens and menus can use them.
 */
@Composable
internal fun libraryTransferLocals(tools: LibraryTransferTools): Array<ProvidedValue<*>> {
    val offline by tools.offline.state.collectAsStateWithLifecycle()
    val undo = LocalUndoSlot.current
    val controls = remember(tools, undo) {
        object : CodexOfflineControls {
            override fun openSheet(codexId: String?) = tools.openTransfers(codexId)
            override fun remove(codexId: String, anchor: UndoAnchor) = tools.offline.remove(codexOfflineOwner(codexId), undo, anchor)
        }
    }
    return arrayOf(LocalCodexOfflineStates provides offline.codexStates, LocalCodexOfflineControls provides controls)
}

/** The Library header's entry to the offline and downloads sheet: a progress pill while work runs. */
@Composable
internal fun LibraryTransfersHeaderButton(tools: LibraryTransferTools) {
    val offline by tools.offline.state.collectAsStateWithLifecycle()
    val batches by tools.downloads.batches.collectAsStateWithLifecycle()
    LibraryTransfersButton(libraryTransfersStatus(offline, batches)) { tools.openTransfers(null) }
}

/** The open offline and downloads sheet, wired to the Activity-owned offline and download owners. */
@Composable
internal fun LibraryTransfersSheetHost(tools: LibraryTransferTools, onOpenPost: suspend (PostDownloadItem) -> Unit) {
    val undo = LocalUndoSlot.current
    val offline by tools.offline.state.collectAsStateWithLifecycle()
    val batches by tools.downloads.batches.collectAsStateWithLifecycle()
    val hidden by tools.downloadRemovals.hidden.collectAsStateWithLifecycle()
    val offlineActions = remember(tools, undo) {
        OfflineRowActions(
            retry = tools.offline::retry,
            cancel = tools.offline::cancel,
            remove = { owner -> tools.offline.remove(owner, undo) },
            removeAll = { tools.offline.removeAll(undo) },
        )
    }
    LibraryTransfersSheet(
        offline = offline,
        offlineActions = offlineActions,
        batches = batches,
        hiddenDownloads = hidden,
        downloadActions = rememberDownloadTileActions(tools, onOpenPost),
        markedCodexId = tools.markedCodexId,
        onDismiss = { tools.showDownloads = false },
    )
}

@Composable
private fun rememberDownloadTileActions(tools: LibraryTransferTools, onOpenPost: suspend (PostDownloadItem) -> Unit): DownloadTileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val undo = LocalUndoSlot.current
    var opening by remember { mutableStateOf(false) }
    val downloads = tools.downloads
    return DownloadTileActions(
        open = { item ->
            if (!opening) {
                opening = true
                scope.launch {
                    try {
                        openPostDownload(item, downloads::localFile,
                            openFile = { openDownloadedPostFile(context, it) },
                            openPost = {
                                tools.showDownloads = false
                                onOpenPost(it)
                            },
                        )
                    } finally { opening = false }
                }
            }
        },
        cancel = downloads::cancel,
        retryFailed = downloads::retryFailed,
        remove = { ids, deleteFiles, anchor -> tools.downloadRemovals.remove(undo, ids, deleteFiles, anchor) },
        localFile = downloads::localFile,
    )
}
