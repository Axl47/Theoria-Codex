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

internal data class PostDownloadRequest(val post: Post, val selectedMedia: SelectedDownloadMedia? = null,
    val marker: String = UUID.randomUUID().toString(), val restored: Boolean = false,
    val untrackedPreparation: Boolean = false, val originLabel: String = "Downloads")
internal data class SelectedDownloadMedia(val media: ImageRef, val pageIndex: Int, val totalPages: Int)

internal enum class PostDownloadStatus { QUEUED, PREPARING, DOWNLOADING, WAITING, SAVED, ALREADY_EXISTS, FAILED, CANCELLED }

internal data class PostDownloadItem(
    val id: Long,
    val request: PostDownloadRequest,
    val status: PostDownloadStatus = PostDownloadStatus.QUEUED,
    val downloadId: Long? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
    val enqueuePending: Boolean = false,
) {
    val active: Boolean get() = status in ACTIVE_DOWNLOAD_STATUSES
}

internal data class PostDownloadBatch(val id: Long, val title: String, val items: List<PostDownloadItem>) {
    val active: Boolean get() = items.any { it.active }
    val savedCount: Int get() = items.count { it.status == PostDownloadStatus.SAVED }
    val existingCount: Int get() = items.count { it.status == PostDownloadStatus.ALREADY_EXISTS }
    val failedCount: Int get() = items.count { it.status == PostDownloadStatus.FAILED }
    val settledCount: Int get() = items.count { !it.active }
}

internal sealed interface PreparedPostDownload {
    data class Transfer(val enqueue: () -> Long?) : PreparedPostDownload
    data class Gallery(val pages: List<PostDownloadRequest>) : PreparedPostDownload
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
    suspend fun findTransfer(marker: String): Long? = null
    suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot
    /** Remove incomplete transfers only; completed public files must remain on the device. */
    suspend fun cancel(downloadId: Long)
    suspend fun deleteFile(item: PostDownloadItem) { error("File deletion is unavailable") }
}

/** All calls and state updates use the owner's dispatcher. Two workers bound preparation and transfers. */
internal class PostDownloadQueue(
    private val scope: CoroutineScope,
    private val backend: PostDownloadBackend,
    private val concurrency: Int = 2,
    private val pollIntervalMs: Long = 1_000,
    private val jobStore: PostDownloadJobStore? = null,
) {
    private val mutableBatches = MutableStateFlow<List<PostDownloadBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()
    private val workers = mutableMapOf<Long, Job>()
    private val locations = mutableMapOf<Long, Pair<Int, Int>>()
    private val expandedItems = mutableMapOf<Long, Set<Long>>()
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
        rebuildLocations()
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
        mutableBatches.value = (mutableBatches.value + PostDownloadBatch(batchId, title, items))
            .let { batches -> batches.filter { it.active } + batches.filterNot { it.active }.takeLast(20) }
            .sortedBy { it.id }
        rebuildLocations()
        var admitted = false
        try {
            checkpoint()
            admitted = true
        } finally {
            if (!admitted) {
                mutableBatches.value = mutableBatches.value.filterNot { it.id == batchId }
                rebuildLocations()
            }
        }
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
        // A restored native transfer may still be waiting for a worker slot.
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

    /** Stop workers before record removal; history-only clearing never deletes completed files. */
    suspend fun removeItems(itemIds: Set<Long>, deleteFiles: Boolean) {
        restore()
        // A confirmation can outlive the parent row while a gallery becomes page downloads.
        val selectedIds = itemIds.flatMapTo(mutableSetOf()) { expandedItems[it].orEmpty() + it }
        val selected = mutableBatches.value.flatMap { it.items }.filter { it.id in selectedIds }
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
                val nativeId = item.downloadId ?: if (item.enqueuePending) backend.findTransfer(item.request.marker) else null
                check(!item.enqueuePending || nativeId != null) { "Android could not identify this transfer" }
                val target = item.copy(downloadId = nativeId)
                cancelNativeTransfer(target)
                if (deleteFiles) backend.deleteFile(target)
            }
            if (result.isSuccess) removed += item.id
            else update(item.id) { it.copy(message = "Could not remove this download. Check file access and try again.") }
        }
        val previous = mutableBatches.value
        mutableBatches.value = previous.mapNotNull { batch ->
            batch.copy(items = batch.items.filterNot { it.id in removed }).takeIf { it.items.isNotEmpty() }
        }
        rebuildLocations()
        try { checkpoint() } catch (error: Exception) {
            mutableBatches.value = previous
            rebuildLocations()
            throw error
        }
        schedule()
    }

    private suspend fun retryItem(item: PostDownloadItem) {
        val nativeId = item.downloadId ?: if (item.enqueuePending) backend.findTransfer(item.request.marker) else null
        // Losing a marker to a native redirect cannot authorize a duplicate of an unknown transfer.
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
        } catch (error: Exception) {
            val message = if (error is DownloadPreparationException) error.message else "Could not save this post. Try again."
            update(item.id) { it.copy(status = PostDownloadStatus.FAILED, message = message) }
            com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation { checkpoint() }
        } finally {
            if (currentItem(item.id)?.status == PostDownloadStatus.CANCELLED) {
                withContext(NonCancellable) { (downloadId ?: currentItem(item.id)?.downloadId)?.let { backend.cancel(it) } }
            }
        }
    }

    private suspend fun resumeExistingTransfer(item: PostDownloadItem): Boolean {
        val nativeId = item.downloadId ?: if (item.enqueuePending) backend.findTransfer(item.request.marker) else return false
        if (nativeId == null) throw DownloadPreparationException(
            "Android could not identify this transfer. Check Downloads; start a new download if needed.")
        if (item.downloadId == null) {
            update(item.id) { it.copy(downloadId = nativeId, enqueuePending = false) }
            checkpoint()
        }
        awaitTransfer(item.id, nativeId)
        return true
    }

    private suspend fun cancelNativeTransfer(item: PostDownloadItem) {
        val id = item.downloadId ?: if (item.enqueuePending) backend.findTransfer(item.request.marker) else null
        id?.let { backend.cancel(it) }
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
                .map { PostDownloadItem(id = ++nextId, request = it.copy(originLabel = origin)) }
            expandedItems[itemId] = children.mapTo(mutableSetOf()) { it.id }
            batch.copy(items = batch.items.flatMap { if (it.id == itemId) children else listOf(it) })
        }
        rebuildLocations()
    }

    private fun rebuildLocations() {
        locations.clear()
        mutableBatches.value.forEachIndexed { batchIndex, batch ->
            batch.items.forEachIndexed { itemIndex, item -> locations[item.id] = batchIndex to itemIndex }
        }
        expandedItems.entries.removeAll { (_, children) -> children.none { it in locations } }
    }

    private fun currentItem(itemId: Long): PostDownloadItem? {
        val (batch, item) = locations[itemId] ?: return null
        return mutableBatches.value.getOrNull(batch)?.items?.getOrNull(item)
    }

    private fun update(itemId: Long, transform: (PostDownloadItem) -> PostDownloadItem) {
        val (batchIndex, itemIndex) = locations[itemId] ?: return
        val batches = mutableBatches.value.toMutableList()
        val batch = batches[batchIndex]
        val items = batch.items.toMutableList()
        items[itemIndex] = transform(items[itemIndex])
        batches[batchIndex] = batch.copy(items = items)
        mutableBatches.value = batches
    }

}

/** Expansion happens after one authoritative resolution; selected Viewer pages never expand again. */
internal fun galleryDownloadPages(post: Post): List<PostDownloadRequest> {
    val media = post.media.ifEmpty { listOfNotNull(post.full) }
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
