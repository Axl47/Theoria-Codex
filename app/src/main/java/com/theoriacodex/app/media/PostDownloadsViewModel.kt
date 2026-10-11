package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.app.viewer.requiresLazyMediaResolution
import com.theoriacodex.app.ui.state.MutationFeedback
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import androidx.core.net.toUri
import java.io.File
import com.theoriacodex.data.repository.CacheSettings
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owned by the Activity so changing routes or recreating its UI cannot restart a download. */
internal class PostDownloadsViewModel(
    context: Context,
    readSettings: suspend () -> CacheSettings,
    resolvePost: suspend (Post) -> Post?,
    exportAnimation: suspend (Post) -> Result<Unit>,
) : ViewModel() {
    // Retain the ViewModel Job and serialize commands with worker continuations off Main.
    private val queueScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO.limitedParallelism(1))
    val feedback = MutationFeedback()
    private val queue = PostDownloadQueue(
        scope = queueScope,
        backend = AndroidPostDownloadBackend(context.applicationContext, readSettings, resolvePost, exportAnimation),
        jobStore = FileBackedPostDownloadJobStore(File(context.filesDir, "theoria_codex/download_jobs.json")),
    )
    val batches = queue.batches
    init { command { queue.restore() } }

    private fun command(action: suspend () -> Unit) {
        queueScope.launch {
            val result = runCatchingPreservingCancellation { action() }
            if (result.isFailure) feedback.add("The download queue could not be saved or restored. Please try again.")
        }
    }

    fun submitPosts(posts: List<Post>, title: String = "Save to device") {
        command { queue.submit(title, posts.map { PostDownloadRequest(it) }) }
    }

    fun submitViewerMedia(post: Post, media: ImageRef, pageIndex: Int, totalPages: Int) {
        command {
            queue.submit("Save to device", listOf(PostDownloadRequest(post, SelectedDownloadMedia(media, pageIndex, totalPages))))
        }
    }

    fun cancel(batchId: Long) {
        command { queue.cancel(batchId) }
    }

    fun retryFailed(batchId: Long) {
        command { queue.retryFailed(batchId) }
    }
}

private class AndroidPostDownloadBackend(
    private val context: Context,
    private val readSettings: suspend () -> CacheSettings,
    private val resolvePost: suspend (Post) -> Post?,
    private val exportAnimation: suspend (Post) -> Result<Unit>,
) : PostDownloadBackend {
    override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
        val settings = readSettings()
        val post = if (request.restored || request.selectedMedia == null && requiresLazyMediaResolution(request.post)) {
            resolvePost(request.post) ?: throw DownloadPreparationException("The provider could not load this post's media.")
        } else request.post
        if (isPixivUgoiraPost(post)) {
            context.animationExportNetworkBlock(settings)?.let { throw DownloadPreparationException(it) }
            exportAnimation(post).getOrElse {
                if (it is CancellationException) throw it
                throw DownloadPreparationException("Could not export this animation. Try again.")
            }
            return PreparedPostDownload.Saved
        }
        if (request.selectedMedia == null) {
            val pages = galleryDownloadPages(post)
            if (pages.isNotEmpty()) return PreparedPostDownload.Gallery(pages)
        }
        return PreparedPostDownload.Transfer { enqueueResolved(request, post, settings) }
    }

    private fun enqueueResolved(request: PostDownloadRequest, post: Post, settings: CacheSettings): Long? {
        val selected = request.selectedMedia
            ?: return PostDownloadService.enqueuePostDownloadId(context, post, settings, request.marker)
        val media = if (request.restored) post.media.ifEmpty { listOfNotNull(post.full) }.getOrNull(selected.pageIndex)
            ?: throw DownloadPreparationException("This gallery page is no longer available.") else selected.media
        return PostDownloadService.enqueueViewerDownloadId(context, post, media, selected.pageIndex, selected.totalPages,
            settings, request.marker)
    }

    override suspend fun findTransfer(marker: String): Long? = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return@withContext null
        manager.query(DownloadManager.Query()).use { cursor ->
            if (cursor == null) return@use null
            while (cursor.moveToNext()) {
                val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).toUri()
                if (uri.fragment == downloadRequestMarker(marker))
                    return@use cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            }
            null
        }
    }

    override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return@withContext DownloadTransferSnapshot(PostDownloadStatus.FAILED, message = "Downloads are unavailable.")
        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                return@withContext DownloadTransferSnapshot(PostDownloadStatus.FAILED, message = "Download was removed from the device.")
            }
            downloadTransferSnapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    override suspend fun cancel(downloadId: Long) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager != null && snapshot(downloadId).status !in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS)) {
            manager.remove(downloadId)
        }
        Unit
    }
}

internal class DownloadPreparationException(message: String) : Exception(message)

internal fun downloadTransferSnapshot(status: Int, reason: Int, downloadedBytes: Long, totalBytes: Long): DownloadTransferSnapshot {
    val downloadStatus = when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> PostDownloadStatus.SAVED
        DownloadManager.STATUS_FAILED -> if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) {
            PostDownloadStatus.ALREADY_EXISTS
        } else PostDownloadStatus.FAILED
        DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> PostDownloadStatus.WAITING
        else -> PostDownloadStatus.DOWNLOADING
    }
    val message = when (downloadStatus) {
        PostDownloadStatus.FAILED -> downloadFailureMessage(reason)
        PostDownloadStatus.WAITING -> when (reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection"
            else -> "Waiting for Android to continue"
        }
        else -> null
    }
    return DownloadTransferSnapshot(downloadStatus, downloadedBytes.coerceAtLeast(0), totalBytes.takeIf { it > 0 }, message)
}

private fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage. Free up space and retry."
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "A file with this name already exists in Downloads."
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable."
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "The connection was interrupted. Try again."
    in 400..599 -> "The provider refused this download (HTTP $reason). Try again."
    else -> "Android could not finish this download. Try again."
}
