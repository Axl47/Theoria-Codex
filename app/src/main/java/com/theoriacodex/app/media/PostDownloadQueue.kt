package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class PostDownloadRequest(val post: Post, val selectedMedia: SelectedDownloadMedia? = null)
internal data class SelectedDownloadMedia(val media: ImageRef, val pageIndex: Int, val totalPages: Int)

internal enum class PostDownloadStatus { QUEUED, PREPARING, DOWNLOADING, WAITING, SAVED, FAILED, CANCELLED }

internal data class PostDownloadItem(
    val id: Long,
    val request: PostDownloadRequest,
    val status: PostDownloadStatus = PostDownloadStatus.QUEUED,
    val downloadId: Long? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
) {
    val active: Boolean get() = status in ACTIVE_DOWNLOAD_STATUSES
}

internal data class PostDownloadBatch(val id: Long, val title: String, val items: List<PostDownloadItem>) {
    val active: Boolean get() = items.any { it.active }
    val savedCount: Int get() = items.count { it.status == PostDownloadStatus.SAVED }
    val failedCount: Int get() = items.count { it.status == PostDownloadStatus.FAILED }
    val settledCount: Int get() = items.count { !it.active }
}

internal sealed interface PreparedPostDownload {
    data class Transfer(val enqueue: () -> Long?) : PreparedPostDownload
    data object Saved : PreparedPostDownload
}

internal data class DownloadTransferSnapshot(
    val status: PostDownloadStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
)

internal interface PostDownloadBackend {
    suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload
    suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot
    /** Remove incomplete transfers only; completed public files must remain on the device. */
    suspend fun cancel(downloadId: Long)
}

/** All calls and state updates use the owner's dispatcher. Two workers bound preparation and transfers. */
internal class PostDownloadQueue(
    private val scope: CoroutineScope,
    private val backend: PostDownloadBackend,
    private val concurrency: Int = 2,
    private val pollIntervalMs: Long = 1_000,
) {
    private val mutableBatches = MutableStateFlow<List<PostDownloadBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()
    private val workers = mutableMapOf<Long, Job>()
    private var nextId = 0L

    init {
        require(concurrency > 0)
        require(pollIntervalMs > 0)
    }

    fun submit(title: String, requests: List<PostDownloadRequest>): Long? {
        val uniqueRequests = requests.distinctBy { it.post.id to it.selectedMedia?.pageIndex }
        if (uniqueRequests.isEmpty()) return null
        val batchId = ++nextId
        val items = uniqueRequests.map { PostDownloadItem(id = ++nextId, request = it) }
        mutableBatches.value = (mutableBatches.value + PostDownloadBatch(batchId, title, items))
            .let { batches -> batches.filter { it.active } + batches.filterNot { it.active }.takeLast(20) }
            .sortedBy { it.id }
        schedule()
        return batchId
    }

    fun cancel(batchId: Long) {
        val activeItems = mutableBatches.value.find { it.id == batchId }?.items?.filter { it.active }.orEmpty()
        activeItems.forEach { item ->
            update(item.id) { it.copy(status = PostDownloadStatus.CANCELLED, message = null) }
            workers[item.id]?.cancel()
        }
        schedule()
    }

    fun retryFailed(batchId: Long) {
        mutableBatches.value.find { it.id == batchId }?.items
            ?.filter { it.status == PostDownloadStatus.FAILED }
            ?.forEach { item -> update(item.id) { it.copy(status = PostDownloadStatus.QUEUED, message = null) } }
        schedule()
    }

    private fun schedule() {
        if (!scope.isActive) return
        val queued = mutableBatches.value.flatMap { it.items }
            .filter { it.status == PostDownloadStatus.QUEUED && it.id !in workers }
            .take((concurrency - workers.size).coerceAtLeast(0))
        queued.forEach { item ->
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                yield()
                execute(item)
            }
            workers[item.id] = worker
            worker.invokeOnCompletion {
                scope.launch {
                    workers.remove(item.id)
                    schedule()
                }
            }
            worker.start()
        }
    }

    private suspend fun execute(item: PostDownloadItem) {
        var downloadId: Long? = item.downloadId
        try {
            downloadId?.let { backend.cancel(it) }
            downloadId = null
            update(item.id) { it.copy(status = PostDownloadStatus.PREPARING, downloadId = null, downloadedBytes = 0, totalBytes = null) }
            val prepared = backend.prepare(item.request)
            currentCoroutineContext().ensureActive()
            when (prepared) {
                PreparedPostDownload.Saved -> update(item.id) { it.copy(status = PostDownloadStatus.SAVED) }
                is PreparedPostDownload.Transfer -> {
                    // Enqueue and retain its ID without a suspension: cancellation cannot lose the system job.
                    downloadId = prepared.enqueue() ?: error("Could not queue this download")
                    val id = requireNotNull(downloadId)
                    update(item.id) { it.copy(status = PostDownloadStatus.DOWNLOADING, downloadId = id) }
                    awaitTransfer(item.id, id)
                }
            }
        } catch (cancelled: CancellationException) {
            if (currentItem(item.id)?.status == PostDownloadStatus.CANCELLED) {
                withContext(NonCancellable) { downloadId?.let { backend.cancel(it) } }
            }
            throw cancelled
        } catch (error: Exception) {
            val message = if (error is DownloadPreparationException) error.message else "Could not save this post. Try again."
            update(item.id) { it.copy(status = PostDownloadStatus.FAILED, message = message) }
        }
    }

    private suspend fun awaitTransfer(itemId: Long, downloadId: Long) {
        while (true) {
            val snapshot = backend.snapshot(downloadId)
            update(itemId) {
                it.copy(status = snapshot.status, downloadedBytes = snapshot.downloadedBytes,
                    totalBytes = snapshot.totalBytes, message = snapshot.message)
            }
            if (snapshot.status !in ACTIVE_DOWNLOAD_STATUSES) return
            delay(pollIntervalMs)
        }
    }

    private fun currentItem(itemId: Long): PostDownloadItem? =
        mutableBatches.value.asSequence().flatMap { it.items }.find { it.id == itemId }

    private fun update(itemId: Long, transform: (PostDownloadItem) -> PostDownloadItem) {
        mutableBatches.value = mutableBatches.value.map { batch ->
            if (batch.items.none { it.id == itemId }) batch else batch.copy(
                items = batch.items.map { if (it.id == itemId) transform(it) else it },
            )
        }
    }
}

private val ACTIVE_DOWNLOAD_STATUSES = setOf(
    PostDownloadStatus.QUEUED, PostDownloadStatus.PREPARING,
    PostDownloadStatus.DOWNLOADING, PostDownloadStatus.WAITING,
)
