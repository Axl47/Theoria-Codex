package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

data class PostDownloadRequest(val post: Post, val selectedMedia: SelectedDownloadMedia? = null,
    val marker: String = UUID.randomUUID().toString(), val restored: Boolean = false,
    val untrackedPreparation: Boolean = false, val originLabel: String = "Downloads")
data class SelectedDownloadMedia(val media: ImageRef, val pageIndex: Int, val totalPages: Int)

enum class PostDownloadStatus { QUEUED, PREPARING, DOWNLOADING, WAITING, SAVED, ALREADY_EXISTS, FAILED, CANCELLED }

data class PostDownloadItem(
    val id: Long,
    val request: PostDownloadRequest,
    val status: PostDownloadStatus = PostDownloadStatus.QUEUED,
    val downloadId: Long? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
    val enqueuePending: Boolean = false,
    val expandedFrom: Long? = null,
) {
    val active: Boolean get() = status in ACTIVE_DOWNLOAD_STATUSES

    fun removedBy(ids: Set<Long>): Boolean = id in ids || expandedFrom in ids
}

data class PostDownloadBatch(val id: Long, val title: String, val items: List<PostDownloadItem>) {
    val active: Boolean get() = items.any { it.active }
    val savedCount: Int get() = items.count { it.status == PostDownloadStatus.SAVED }
    val existingCount: Int get() = items.count { it.status == PostDownloadStatus.ALREADY_EXISTS }
    val failedCount: Int get() = items.count { it.status == PostDownloadStatus.FAILED }
    val settledCount: Int get() = items.count { !it.active }
}

fun List<PostDownloadBatch>.savedBytes(): Long =
    sumOf { batch -> batch.items.filter { it.status == PostDownloadStatus.SAVED }.sumOf(PostDownloadItem::downloadedBytes) }

sealed interface PreparedPostDownload {
    data class Transfer(val enqueue: () -> Long?) : PreparedPostDownload
    data class Gallery(val pages: List<PostDownloadRequest>) : PreparedPostDownload
    data object Saved : PreparedPostDownload
}

data class DownloadTransferSnapshot(
    val status: PostDownloadStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
)

interface PostDownloadBackend {
    suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload
    suspend fun findTransfer(marker: String): Long? = null
    suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot
    suspend fun cancel(downloadId: Long)
    suspend fun deleteFile(item: PostDownloadItem) { error("File deletion is unavailable") }
}

class PostDownloadQueue(
    private val scope: CoroutineScope,
    private val backend: PostDownloadBackend,
    private val concurrency: Int = 2,
    private val pollIntervalMs: Long = 1_000,
    private val jobStore: PostDownloadJobStore? = null,
    private val startGate: suspend () -> Unit = {},
) {
    private val mutableBatches = MutableStateFlow<List<PostDownloadBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()
    private val workers = mutableMapOf<Long, Job>()
    private var nextId = 0L
    private val persistence = Mutex()
    private val restoration = Mutex()
    private var restored = false

    suspend fun restore() = restoration.withLock {
        if (restored) return@withLock
        val loaded = jobStore?.load().orEmpty()
        loaded.flatMap { it.items }.filter { it.status == PostDownloadStatus.CANCELLED }
            .forEach { item -> cancelNativeTransfer(item) }
        mutableBatches.value = loaded
        nextId = loaded.flatMap { listOf(it.id) + it.items.map(PostDownloadItem::id) }.maxOrNull() ?: 0L
        restored = true
        schedule()
    }

    private suspend fun checkpoint() = persistence.withLock { jobStore?.save(mutableBatches.value); Unit }

    init {
        require(concurrency > 0)
        require(pollIntervalMs > 0)
    }

    suspend fun submit(title: String, requests: List<PostDownloadRequest>): Long? {
        restore()
        val uniqueRequests = requests.distinctBy { it.post.id to it.selectedMedia?.pageIndex }
        if (uniqueRequests.isEmpty()) return null
        val batchId = ++nextId
        val items = uniqueRequests.map { PostDownloadItem(id = ++nextId, request = it) }
        val admitted = (mutableBatches.value + PostDownloadBatch(batchId, title, items))
            .let { batches -> batches.filter { it.active } + batches.filterNot { it.active }.takeLast(20) }
            .sortedBy { it.id }
        commit(admitted) { mutableBatches.value.filterNot { it.id == batchId } }
        schedule()
        return batchId
    }

    suspend fun cancel(batchId: Long) {
        restore()
        val activeItems = mutableBatches.value.find { it.id == batchId }?.items?.filter { it.active }.orEmpty()
        activeItems.forEach { item ->
            update(item.id) { it.copy(status = PostDownloadStatus.CANCELLED, message = null) }
            workers[item.id]?.cancel()
        }
        checkpoint()
        withContext(NonCancellable) { activeItems.forEach { item -> cancelNativeTransfer(item) } }
        schedule()
    }

    suspend fun retryFailed(batchId: Long) {
        restore()
        val failed = mutableBatches.value.find { it.id == batchId }?.items.orEmpty()
            .filter { it.status == PostDownloadStatus.FAILED }
        failed.forEach { retryItem(it) }
        checkpoint()
        schedule()
    }

    suspend fun removeItems(itemIds: Set<Long>, deleteFiles: Boolean): Boolean {
        restore()
        val selected = mutableBatches.value.flatMap { it.items }.filter { it.removedBy(itemIds) }
        selected.filter { it.active }.forEach { item ->
            update(item.id) { it.copy(status = PostDownloadStatus.CANCELLED, message = null) }
            workers[item.id]?.cancel()
        }
        checkpoint()
        selected.forEach { workers[it.id]?.join() }
        val removed = mutableSetOf<Long>()
        selected.forEach { original ->
            val item = currentItem(original.id) ?: return@forEach
            val result = com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation {
                val nativeId = nativeTransferId(item)
                check(!item.enqueuePending || nativeId != null) { "Android could not identify this transfer" }
                val target = item.copy(downloadId = nativeId)
                cancelNativeTransfer(target)
                if (deleteFiles) backend.deleteFile(target)
            }
            if (result.isSuccess) removed += item.id
            else update(item.id) { it.copy(message = "Could not remove this download. Check file access and try again.") }
        }
        val previous = mutableBatches.value
        val remaining = previous.mapNotNull { batch ->
            batch.copy(items = batch.items.filterNot { it.id in removed }).takeIf { it.items.isNotEmpty() }
        }
        commit(remaining) { previous }
        schedule()
        return mutableBatches.value.none { batch -> batch.items.any { it.removedBy(itemIds) } }
    }

    private suspend fun retryItem(item: PostDownloadItem) {
        val nativeId = nativeTransferId(item)
        if (item.enqueuePending && nativeId == null) return
        val nativeStatus = nativeId?.let { backend.snapshot(it).status }
        if (nativeStatus == PostDownloadStatus.SAVED || nativeStatus == PostDownloadStatus.ALREADY_EXISTS) {
            update(item.id) { it.copy(status = requireNotNull(nativeStatus), downloadId = nativeId, enqueuePending = false, message = null) }
        } else if (nativeStatus in ACTIVE_DOWNLOAD_STATUSES) {
            update(item.id) { it.copy(status = PostDownloadStatus.QUEUED, downloadId = nativeId, enqueuePending = false, message = null) }
        } else {
            nativeId?.let { backend.cancel(it) }
            update(item.id) { it.copy(status = PostDownloadStatus.QUEUED, downloadId = null, enqueuePending = false,
                request = if (nativeId == null) it.request else it.request.copy(marker = UUID.randomUUID().toString()), message = null) }
        }
    }

    private fun schedule() {
        if (!scope.isActive) return
        val queued = mutableBatches.value.flatMap { it.items }
            .filter { it.status == PostDownloadStatus.QUEUED && it.id !in workers }
            .take((concurrency - workers.size).coerceAtLeast(0))
        queued.forEach { item ->
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                yield()
                startGate()
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

    @Suppress("SwallowedException")
    private suspend fun execute(item: PostDownloadItem) {
        var downloadId: Long? = item.downloadId
        try {
            if (resumeExistingTransfer(item)) return
            update(item.id) { it.copy(status = PostDownloadStatus.PREPARING, downloadId = null, downloadedBytes = 0, totalBytes = null) }
            checkpoint()
            val prepared = backend.prepare(item.request)
            currentCoroutineContext().ensureActive()
            when (prepared) {
                PreparedPostDownload.Saved -> update(item.id) { it.copy(status = PostDownloadStatus.SAVED) }
                is PreparedPostDownload.Gallery -> expandGallery(item.id, prepared.pages)
                is PreparedPostDownload.Transfer -> {
                    update(item.id) { it.copy(enqueuePending = true) }
                    checkpoint()
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        downloadId = prepared.enqueue() ?: error("Could not queue this download")
                        update(item.id) {
                            it.copy(status = if (it.status == PostDownloadStatus.CANCELLED) it.status else PostDownloadStatus.DOWNLOADING,
                                downloadId = downloadId, enqueuePending = false)
                        }
                        checkpoint()
                    }
                    awaitTransfer(item.id, requireNotNull(downloadId))
                }
            }
            checkpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DownloadPreparationException) {
            markFailed(item.id, error.message)
        } catch (error: Exception) {
            markFailed(item.id, "Could not save this post. Try again.")
        } finally {
            if (currentItem(item.id)?.status == PostDownloadStatus.CANCELLED) {
                withContext(NonCancellable) { (downloadId ?: currentItem(item.id)?.downloadId)?.let { backend.cancel(it) } }
            }
        }
    }

    private suspend fun markFailed(itemId: Long, message: String?) {
        update(itemId) { it.copy(status = PostDownloadStatus.FAILED, message = message) }
        com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { checkpoint() }
    }

    private suspend fun resumeExistingTransfer(item: PostDownloadItem): Boolean {
        if (item.downloadId == null && !item.enqueuePending) return false
        val nativeId = nativeTransferId(item) ?: throw DownloadPreparationException(
            "Android could not identify this transfer. Check Downloads; start a new download if needed.")
        if (item.downloadId == null) {
            update(item.id) { it.copy(downloadId = nativeId, enqueuePending = false) }
            checkpoint()
        }
        awaitTransfer(item.id, nativeId)
        return true
    }

    private suspend fun cancelNativeTransfer(item: PostDownloadItem) {
        nativeTransferId(item)?.let { backend.cancel(it) }
    }

    private suspend fun nativeTransferId(item: PostDownloadItem): Long? =
        item.downloadId ?: if (item.enqueuePending) backend.findTransfer(item.request.marker) else null

    private suspend fun commit(next: List<PostDownloadBatch>, rollback: () -> List<PostDownloadBatch>) {
        mutableBatches.value = next
        var committed = false
        try {
            checkpoint()
            committed = true
        } finally {
            if (!committed) mutableBatches.value = rollback()
        }
    }

    private suspend fun awaitTransfer(itemId: Long, downloadId: Long) {
        while (true) {
            val previousStatus = currentItem(itemId)?.status
            val snapshot = backend.snapshot(downloadId)
            update(itemId) {
                it.copy(status = snapshot.status, downloadedBytes = snapshot.downloadedBytes,
                    totalBytes = snapshot.totalBytes, message = snapshot.message)
            }
            if (previousStatus != snapshot.status) checkpoint()
            if (snapshot.status !in ACTIVE_DOWNLOAD_STATUSES) return
            delay(pollIntervalMs)
        }
    }

    private fun expandGallery(itemId: Long, pages: List<PostDownloadRequest>) {
        val origin = currentItem(itemId)?.request?.originLabel ?: return
        require(pages.isNotEmpty() && pages.all { it.selectedMedia != null })
        mutableBatches.value = mutableBatches.value.map { batch ->
            if (batch.items.none { it.id == itemId }) return@map batch
            val seen = batch.items.filterNot { it.id == itemId }
                .mapTo(mutableSetOf()) { it.request.post.id to it.request.selectedMedia?.pageIndex }
            val children = pages.filter { seen.add(it.post.id to it.selectedMedia?.pageIndex) }
                .map { PostDownloadItem(id = ++nextId, request = it.copy(originLabel = origin), expandedFrom = itemId) }
            batch.copy(items = batch.items.flatMap { if (it.id == itemId) children else listOf(it) })
        }
    }

    private fun currentItem(itemId: Long): PostDownloadItem? =
        mutableBatches.value.firstNotNullOfOrNull { batch -> batch.items.find { it.id == itemId } }

    private fun update(itemId: Long, transform: (PostDownloadItem) -> PostDownloadItem) {
        val batches = mutableBatches.value
        val batchIndex = batches.indexOfFirst { batch -> batch.items.any { it.id == itemId } }
        if (batchIndex < 0) return
        val batch = batches[batchIndex]
        val items = batch.items.map { item -> if (item.id == itemId) transform(item) else item }
        mutableBatches.value = batches.toMutableList().apply { this[batchIndex] = batch.copy(items = items) }
    }

}

fun galleryDownloadPages(post: Post): List<PostDownloadRequest> {
    val media = post.downloadablePages
    if ((post.mediaCount ?: media.size) > media.size) {
        throw DownloadPreparationException("The provider could not load the complete gallery. Try again.")
    }
    if (media.size < 2) return emptyList()
    return media.mapIndexed { index, ref -> PostDownloadRequest(post, SelectedDownloadMedia(ref, index, media.size)) }
}

private val ACTIVE_DOWNLOAD_STATUSES = setOf(
    PostDownloadStatus.QUEUED, PostDownloadStatus.PREPARING,
    PostDownloadStatus.DOWNLOADING, PostDownloadStatus.WAITING,
)
