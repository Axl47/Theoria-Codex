package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDownloadQueueRulesTest {
    @Test
    fun `batch counts and saved bytes only include their own statuses`() {
        val items = PostDownloadStatus.entries.mapIndexed { index, status ->
            PostDownloadItem(index + 1L, request(index + 1), status, downloadedBytes = 10L * (index + 1))
        }
        val batch = PostDownloadBatch(100, "All", items)
        assertTrue(batch.active)
        assertEquals(1, batch.savedCount)
        assertEquals(1, batch.existingCount)
        assertEquals(1, batch.failedCount)
        assertEquals(4, batch.settledCount)
        assertFalse(PostDownloadBatch(101, "Done", items.filterNot { it.active }).active)
        assertFalse(request(1).restored)

        val savedBytes = listOf(batch, PostDownloadBatch(102, "More", listOf(
            PostDownloadItem(50, request(50), PostDownloadStatus.SAVED, downloadedBytes = 7),
            PostDownloadItem(51, request(51), PostDownloadStatus.FAILED, downloadedBytes = 1_000),
        ))).savedBytes()
        assertEquals(10L * (PostDownloadStatus.SAVED.ordinal + 1) + 7, savedBytes)
    }

    @Test
    fun `the queue rejects a non-positive worker count or poll interval and accepts one`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { PostDownloadQueue(backgroundScope, Backend(), concurrency = 0) }
        assertThrows(IllegalArgumentException::class.java) { PostDownloadQueue(backgroundScope, Backend(), pollIntervalMs = 0) }
        PostDownloadQueue(backgroundScope, Backend(), concurrency = 1, pollIntervalMs = 1)
    }

    @Test
    fun `duplicate posts collapse but different pages of one post stay separate`() = runTest {
        val queue = PostDownloadQueue(backgroundScope, Backend())
        queue.submit("Mixed", listOf(
            request(1), request(1),
            request(2, page = 0), request(2, page = 1), request(2, page = 1),
        ))
        val items = queue.batches.value.single().items
        assertEquals(listOf("1" to null, "2" to 0, "2" to 1),
            items.map { it.request.post.id.sourcePostId to it.request.selectedMedia?.pageIndex })
    }

    @Test
    fun `restoring loads once and later jobs get fresh ids`() = runTest {
        var loads = 0
        val stored = listOf(PostDownloadBatch(7, "Old", listOf(PostDownloadItem(9, request(1), PostDownloadStatus.SAVED))))
        val store = object : PostDownloadJobStore {
            override suspend fun load(): List<PostDownloadBatch> { loads++; return stored }
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val queue = PostDownloadQueue(backgroundScope, Backend(), jobStore = store)
        queue.restore()
        queue.restore()
        val batchId = requireNotNull(queue.submit("New", listOf(request(2))))
        assertEquals(1, loads)
        assertEquals(10L, batchId)
        assertEquals(listOf(7L, 10L), queue.batches.value.map { it.id })
        assertEquals(11L, queue.batches.value.last().items.single().id)
    }

    @Test
    fun `restoring a cancelled job releases its native transfer`() = runTest {
        val backend = Backend()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Old", listOf(
                PostDownloadItem(2, request(1), PostDownloadStatus.CANCELLED, downloadId = 42),
                PostDownloadItem(3, request(2), PostDownloadStatus.SAVED, downloadId = 43),
            )))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        PostDownloadQueue(backgroundScope, backend, jobStore = store).restore()
        assertEquals(listOf(42L), backend.cancelled)
    }

    @Test
    fun `cancel and retry affect only the named batch and only the relevant items`() = runTest {
        val backend = Backend()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.FAILED)
        backend.states[2] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 4)
        val first = requireNotNull(queue.submit("First", listOf(request(1), request(2))))
        val second = requireNotNull(queue.submit("Second", listOf(request(3))))
        runCurrent()

        queue.cancel(999)
        queue.retryFailed(second)
        queue.retryFailed(999)
        runCurrent()
        assertEquals(listOf("1", "2", "3"), backend.prepared)
        assertTrue(backend.cancelled.isEmpty())

        queue.cancel(first)
        runCurrent()
        val (firstBatch, secondBatch) = queue.batches.value
        assertEquals(listOf(PostDownloadStatus.FAILED, PostDownloadStatus.SAVED), firstBatch.items.map { it.status })
        assertEquals(PostDownloadStatus.DOWNLOADING, secondBatch.items.single().status)
        assertTrue(backend.cancelled.isEmpty())
    }

    @Test
    fun `retry resumes an active native transfer and adopts a finished one without a new download`() = runTest {
        val backend = Backend()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Old", listOf(
                PostDownloadItem(2, request(1), PostDownloadStatus.FAILED, downloadId = 20),
                PostDownloadItem(3, request(2), PostDownloadStatus.FAILED, downloadId = 30),
                PostDownloadItem(4, request(3), PostDownloadStatus.FAILED, downloadId = 40),
                PostDownloadItem(5, request(4), PostDownloadStatus.FAILED, enqueuePending = true),
            )))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        backend.states[20] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        backend.states[30] = DownloadTransferSnapshot(PostDownloadStatus.ALREADY_EXISTS)
        backend.states[40] = DownloadTransferSnapshot(PostDownloadStatus.WAITING)
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 1, jobStore = store)
        queue.retryFailed(1)

        val items = queue.batches.value.single().items
        assertEquals(listOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS, PostDownloadStatus.QUEUED,
            PostDownloadStatus.FAILED), items.map { it.status })
        assertEquals(listOf<Long?>(20, 30, 40, null), items.map { it.downloadId })
        assertTrue(backend.cancelled.isEmpty())
        assertTrue(backend.prepared.isEmpty())
    }

    @Test
    fun `removing history records deletes their files on request and leaves a running sibling alone`() = runTest {
        val backend = Backend()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        backend.states[2] = DownloadTransferSnapshot(PostDownloadStatus.FAILED)
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 3)
        queue.submit("Mixed", listOf(request(1), request(2), request(3)))
        runCurrent()
        val ids = queue.batches.value.single().items.map { it.id }

        queue.removeItems(setOf(ids[0], ids[1]), deleteFiles = true)
        assertEquals(listOf("1", "2"), backend.deleted)
        assertEquals(listOf(ids[2]), queue.batches.value.single().items.map { it.id })
        assertEquals(PostDownloadStatus.DOWNLOADING, queue.batches.value.single().items.single().status)

        queue.removeItems(setOf(ids[2]), deleteFiles = false)
        assertEquals(3L, backend.cancelled.last())
        assertEquals(2, backend.deleted.size)
        assertTrue(queue.batches.value.isEmpty())
    }

    @Test
    fun `removal refuses to drop a pending enqueue whose native transfer cannot be found`() = runTest {
        val backend = Backend()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Gap", listOf(
                PostDownloadItem(2, request(1), PostDownloadStatus.FAILED, enqueuePending = true),
                PostDownloadItem(3, request(2), PostDownloadStatus.FAILED, enqueuePending = true),
            )))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val queue = PostDownloadQueue(backgroundScope, backend, jobStore = store)
        queue.restore()
        backend.found = mapOf(queue.batches.value.single().items[1].request.marker to 77L)
        queue.removeItems(setOf(2, 3), deleteFiles = true)

        val remaining = queue.batches.value.single().items.single()
        assertEquals(2L, remaining.id)
        assertTrue(remaining.message.orEmpty().contains("Could not remove"))
        assertEquals(listOf(77L), backend.cancelled)
        assertEquals(listOf("2"), backend.deleted)
    }

    @Test
    fun `failures show the provider's message or a generic one`() = runTest {
        val backend = Backend()
        backend.prepareOverride = { request ->
            when (request.post.id.sourcePostId) {
                "1" -> throw DownloadPreparationException("Provider says no")
                "2" -> throw IllegalStateException("internal detail")
                else -> PreparedPostDownload.Saved
            }
        }
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 3)
        queue.submit("Failures", listOf(request(1), request(2), request(3)))
        runCurrent()
        val items = queue.batches.value.single().items
        assertEquals(listOf("Provider says no", "Could not save this post. Try again.", null), items.map { it.message })
        assertEquals(listOf(PostDownloadStatus.FAILED, PostDownloadStatus.FAILED, PostDownloadStatus.SAVED), items.map { it.status })
    }

    @Test
    fun `a restored job resumes its transfer, and one with an unknown transfer fails without a new download`() = runTest {
        val backend = Backend()
        backend.states[5] = DownloadTransferSnapshot(PostDownloadStatus.SAVED, 9, 9)
        val saves = mutableListOf<List<PostDownloadBatch>>()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Old", listOf(
                PostDownloadItem(2, request(1), PostDownloadStatus.QUEUED, downloadId = 5),
                PostDownloadItem(3, request(2), PostDownloadStatus.QUEUED, enqueuePending = true),
            )))
            override suspend fun save(batches: List<PostDownloadBatch>) { saves += batches }
        }
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 2, jobStore = store)
        queue.restore()
        runCurrent()

        val (resumed, lost) = queue.batches.value.single().items
        assertEquals(PostDownloadStatus.SAVED, resumed.status)
        assertEquals(9L, resumed.downloadedBytes)
        assertEquals(PostDownloadStatus.FAILED, lost.status)
        assertTrue(lost.message.orEmpty().startsWith("Android could not identify"))
        assertTrue(backend.prepared.isEmpty())
        assertTrue(saves.isNotEmpty())
    }

    @Test
    fun `polling saves on status changes only and reports bytes at each step`() = runTest {
        val backend = Backend()
        var saveCount = 0
        val store = object : PostDownloadJobStore {
            override suspend fun load() = emptyList<PostDownloadBatch>()
            override suspend fun save(batches: List<PostDownloadBatch>) { saveCount++ }
        }
        val queue = PostDownloadQueue(backgroundScope, backend, pollIntervalMs = 500, jobStore = store)
        queue.submit("One", listOf(request(1)))
        runCurrent()
        val afterEnqueue = saveCount

        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING, 10, 100)
        advanceTimeBy(500); runCurrent()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING, 60, 100)
        advanceTimeBy(500); runCurrent()
        assertEquals(60L, queue.batches.value.single().items.single().downloadedBytes)
        assertEquals(afterEnqueue, saveCount)

        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED, 100, 100)
        advanceTimeBy(500); runCurrent()
        assertEquals(PostDownloadStatus.SAVED, queue.batches.value.single().items.single().status)
        assertTrue(saveCount > afterEnqueue)
        val prepared = backend.prepared.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(prepared, backend.prepared.size)
    }

    @Test
    fun `gallery pages inherit the origin and skip pages already queued`() = runTest {
        val backend = Backend()
        backend.prepareOverride = { request ->
            if (request.selectedMedia == null) PreparedPostDownload.Gallery(galleryDownloadPages(request.post))
            else PreparedPostDownload.Saved
        }
        val gallery = request(1).post.copy(media = List(3) { MEDIA }, mediaCount = 3)
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 1)
        queue.submit("Gallery", listOf(
            PostDownloadRequest(gallery, originLabel = "Favorites"),
            PostDownloadRequest(gallery, SelectedDownloadMedia(MEDIA, pageIndex = 1, totalPages = 3)),
        ))
        runCurrent()
        val items = queue.batches.value.single().items
        assertEquals(listOf(0, 2, 1), items.map { it.request.selectedMedia?.pageIndex })
        assertEquals(3, items.size)
        assertEquals(setOf("Favorites", "Downloads"), items.map { it.request.originLabel }.toSet())
        assertTrue(items.all { it.status == PostDownloadStatus.SAVED })
    }

    @Test
    fun `galleryDownloadPages follows the provider's page count`() {
        val three = request(1).post.copy(media = List(3) { MEDIA }, mediaCount = 3)
        assertEquals(listOf(0, 1, 2), galleryDownloadPages(three).map { it.selectedMedia?.pageIndex })
        assertEquals(3, galleryDownloadPages(three).first().selectedMedia?.totalPages)
        val two = request(1).post.copy(media = List(2) { MEDIA }, mediaCount = null)
        assertEquals(2, galleryDownloadPages(two).size)
        assertTrue(galleryDownloadPages(request(1).post.copy(media = listOf(MEDIA), mediaCount = 1)).isEmpty())
        assertTrue(galleryDownloadPages(request(1).post.copy(media = emptyList(), mediaCount = null)).isEmpty())
        val incomplete = request(1).post.copy(media = List(2) { MEDIA }, mediaCount = 3)
        assertThrows(DownloadPreparationException::class.java) { galleryDownloadPages(incomplete) }
    }

    private class Backend : PostDownloadBackend {
        val prepared = mutableListOf<String>()
        val cancelled = mutableListOf<Long>()
        val deleted = mutableListOf<String>()
        val states = mutableMapOf<Long, DownloadTransferSnapshot>()
        var found: Map<String, Long> = emptyMap()
        var prepareOverride: (suspend (PostDownloadRequest) -> PreparedPostDownload)? = null
        private var nextId = 0L

        override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
            prepared += request.post.id.sourcePostId
            return prepareOverride?.invoke(request) ?: PreparedPostDownload.Transfer { ++nextId }
        }
        override suspend fun findTransfer(marker: String): Long? = found[marker]
        override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot =
            states[downloadId] ?: DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING)
        override suspend fun cancel(downloadId: Long) { cancelled += downloadId }
        override suspend fun deleteFile(item: PostDownloadItem) { deleted += item.request.post.id.sourcePostId }
    }

    private fun request(id: Int, page: Int? = null) = PostDownloadRequest(
        Post(PostId(SourceKey.PIXIV, "$id"), MEDIA, MEDIA, listOf(MEDIA), null, null, null,
            emptyList(), emptyList(), null, null, title = "Post $id"),
        selectedMedia = page?.let { SelectedDownloadMedia(MEDIA, it, 3) },
    )
}

private val MEDIA = ImageRef("https://example.com/full.jpg", null, "image/jpeg")
