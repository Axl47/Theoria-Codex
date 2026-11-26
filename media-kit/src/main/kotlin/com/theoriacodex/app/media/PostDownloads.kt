package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import com.theoriacodex.app.viewer.requiresLazyMediaResolution
import com.theoriacodex.app.ui.state.MutationFeedback
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import com.theoriacodex.app.viewer.EpisodeClipException
import com.theoriacodex.app.viewer.EpisodeClipRange
import com.theoriacodex.app.viewer.label
import com.theoriacodex.app.viewer.exportEpisodeClip
import com.theoriacodex.app.viewer.videoPlaybackInfrastructure
import com.theoriacodex.data.repository.CacheSettings
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PostDownloads(
    context: Context,
    jobFile: File,
    parentScope: CoroutineScope,
    readSettings: suspend () -> CacheSettings,
    resolvePost: suspend (Post) -> Post?,
    exportAnimation: suspend (Post, String) -> Result<Unit>,
    sourcesReady: suspend () -> Unit,
    private val onClipWork: () -> Unit = {},
) {
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val queueScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + queueDispatcher)
    val feedback = MutationFeedback()
    private val backend = AndroidPostDownloadBackend(context.applicationContext, readSettings, resolvePost, exportAnimation)
    private val queue = PostDownloadQueue(scope = queueScope, backend = backend, jobStore = FileBackedPostDownloadJobStore(jobFile),
        startGate = sourcesReady)
    val batches = queue.batches
    init { command { queue.restore() } }

    private fun command(action: suspend () -> Unit) {
        queueScope.launch {
            val result = runCatchingPreservingCancellation { action() }
            if (result.isFailure) feedback.add("The download queue could not be saved or restored. Please try again.")
        }
    }

    fun submitPosts(posts: List<Post>, title: String = "Save to device", originLabel: String = "Downloads") {
        command { queue.submit(title, posts.map { PostDownloadRequest(it, originLabel = originLabel) }) }
    }

    fun submitViewerMedia(post: Post, media: ImageRef, pageIndex: Int, totalPages: Int, originLabel: String = "Viewer") {
        command {
            queue.submit("Save to device", listOf(PostDownloadRequest(post, SelectedDownloadMedia(media, pageIndex, totalPages), originLabel = originLabel)))
        }
    }

    fun submitClip(post: Post, clip: EpisodeClipRequest) {
        onClipWork()
        command { queue.submit("Clip", listOf(PostDownloadRequest(post, originLabel = "Clip ${clip.range.label}", clip = clip))) }
    }

    suspend fun localFile(item: PostDownloadItem): DownloadedPostFile? = backend.localFile(item)

    suspend fun removeNow(itemIds: Set<Long>, deleteFiles: Boolean): Boolean = withContext(queueDispatcher + NonCancellable) {
        queue.removeItems(itemIds, deleteFiles)
    }

    fun cancel(batchId: Long) {
        command { queue.cancel(batchId) }
    }

    fun retryFailed(batchId: Long) {
        if (batches.value.any { batch -> batch.id == batchId && batch.items.any { it.request.clip != null } }) onClipWork()
        command { queue.retryFailed(batchId) }
    }
}

internal class AndroidPostDownloadBackend(
    private val context: Context,
    private val readSettings: suspend () -> CacheSettings,
    private val resolvePost: suspend (Post) -> Post?,
    private val exportAnimation: suspend (Post, String) -> Result<Unit>,
) : PostDownloadBackend {
    override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
        request.clip?.let { clip -> return prepareClip(request, clip) }
        if (request.restored && request.post.id.source == com.theoriacodex.domain.model.SourceKey.PIXIV &&
            AnimationExportDestination(context).findCompleted(request.marker) != null) return PreparedPostDownload.Saved
        val settings = readSettings()
        val post = if (request.restored || request.selectedMedia == null && requiresLazyMediaResolution(request.post)) {
            resolvePost(request.post) ?: throw DownloadPreparationException("The provider could not load this post's media.")
        } else request.post
        if (isPixivUgoiraPost(post)) {
            if (request.untrackedPreparation) throw DownloadPreparationException(
                "An older animation save was interrupted. Check saved videos before starting a new download.")
            context.animationExportNetworkBlock(settings)?.let { throw DownloadPreparationException(it) }
            exportAnimation(post, request.marker).getOrElse {
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

    private suspend fun prepareClip(request: PostDownloadRequest, clip: EpisodeClipRequest): PreparedPostDownload {
        val destination = AnimationExportDestination(context)
        if (destination.findCompleted(request.marker) != null) return PreparedPostDownload.Saved
        val stream = clip.stream ?: throw DownloadPreparationException("This clip was interrupted. Clip it again from the episode.")
        context.animationExportNetworkBlock(readSettings())?.let { throw DownloadPreparationException(it) }
        return PreparedPostDownload.Encode { onProgress ->
            val encoded = File(context.cacheDir, "$CLIP_CACHE_DIRECTORY/${request.marker}.mp4")
            try {
                val result = try {
                    exportEpisodeClip(context, context.videoPlaybackInfrastructure(), stream, clip.range, clip.subtitleLabel, clip.audioLabel,
                        encoded, onProgress)
                } catch (error: EpisodeClipException) {
                    throw DownloadPreparationException(error.message.orEmpty(), error)
                }
                withContext(Dispatchers.IO) {
                    destination.save(clipFileName(request.post, clip.range), request.marker) { descriptor ->
                        encoded.inputStream().use { input -> input.copyTo(FileOutputStream(descriptor)) }
                    }
                }
                "Saved without subtitles".takeIf { clip.subtitleLabel != null && !result.subtitlesBurned }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { encoded.delete() }
            }
        }
    }

    private fun enqueueResolved(request: PostDownloadRequest, post: Post, settings: CacheSettings): Long? {
        val selected = request.selectedMedia?.let { page ->
            if (!request.restored) return@let page
            page.copy(media = post.downloadablePages.getOrNull(page.pageIndex)
                ?: throw DownloadPreparationException("This gallery page is no longer available."))
        }
        return PostDownloadService.enqueuePostDownloadId(context, post, selected, settings, request.marker)
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

    suspend fun localFile(item: PostDownloadItem): DownloadedPostFile? =
        withContext(Dispatchers.IO) { PostDownloadFiles(context).find(item) }

    override suspend fun deleteFile(item: PostDownloadItem) {
        withContext(Dispatchers.IO) { PostDownloadFiles(context).delete(item) }
    }

    override suspend fun cancel(downloadId: Long) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager != null && snapshot(downloadId).status !in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS)) {
            manager.remove(downloadId)
        }
        Unit
    }
}

internal class DownloadPreparationException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun clipFileName(post: Post, range: EpisodeClipRange): String {
    val title = post.title?.takeIf(String::isNotBlank)?.take(80) ?: "Episode"
    return "$title ${range.label}.mp4".replace(FILE_NAME_RESERVED, ".")
}

private val FILE_NAME_RESERVED = Regex("""[\\/:*?"<>|]""")
private const val CLIP_CACHE_DIRECTORY = "episode-clips"

fun downloadTransferSnapshot(status: Int, reason: Int, downloadedBytes: Long, totalBytes: Long): DownloadTransferSnapshot {
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
