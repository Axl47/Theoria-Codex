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
import com.theoriacodex.app.media.CodexOfflineSheetOpener
import com.theoriacodex.app.media.DownloadTileActions
import com.theoriacodex.app.media.LibraryTransfersButton
import com.theoriacodex.app.media.LibraryTransfersSheet
import com.theoriacodex.app.media.LocalCodexOfflineSheetOpener
import com.theoriacodex.app.media.LocalCodexOfflineStates
import com.theoriacodex.app.media.OfflineRowActions
import com.theoriacodex.app.media.PostDownloadItem
import com.theoriacodex.app.media.libraryTransfersStatus
import com.theoriacodex.app.media.openDownloadedPostFile
import com.theoriacodex.app.media.openPostDownload
import com.theoriacodex.app.media.DownloadRemovalRows
import kotlinx.coroutines.launch

/**
 * The offline ring's state for every Codex kept offline and the opener for the Library's offline sheet,
 * provided over the whole app so Codex tiles and detail screens can draw the ring.
 */
@Composable
internal fun libraryTransferLocals(tools: LibraryTransferTools): Array<ProvidedValue<*>> {
    val offline by tools.offline.state.collectAsStateWithLifecycle()
    val opener = remember(tools) { CodexOfflineSheetOpener(tools::openTransfers) }
    return arrayOf(LocalCodexOfflineStates provides offline.codexStates, LocalCodexOfflineSheetOpener provides opener)
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
    val offline by tools.offline.state.collectAsStateWithLifecycle()
    val batches by tools.downloads.batches.collectAsStateWithLifecycle()
    val pendingDownloads by tools.downloadRemovals.pending.collectAsStateWithLifecycle()
    val offlineActions = remember(tools) {
        OfflineRowActions(
            retry = tools.offline::retry,
            cancel = tools.offline::cancel,
            remove = { owner -> tools.offline.removeDeferred(owner) },
            removeAll = tools.offline::removeAll,
            actOnRemoval = tools.offline::actOnRemoval,
        )
    }
    LibraryTransfersSheet(
        offline = offline,
        offlineActions = offlineActions,
        batches = batches,
        downloadRemovals = DownloadRemovalRows(pendingDownloads, tools.downloadRemovals::act),
        downloadActions = rememberDownloadTileActions(tools, onOpenPost),
        markedCodexId = tools.markedCodexId,
        onDismiss = { tools.showDownloads = false },
        onLeave = {
            tools.downloadRemovals.commitAll()
            tools.offline.commitRemovals()
        },
    )
}

@Composable
private fun rememberDownloadTileActions(tools: LibraryTransferTools, onOpenPost: suspend (PostDownloadItem) -> Unit): DownloadTileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        remove = tools.downloadRemovals::remove,
        localFile = downloads::localFile,
    )
}
