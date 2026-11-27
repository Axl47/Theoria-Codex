package com.theoriacodex.app.media

import android.app.DownloadManager
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDownloadQueueTest {
    @Test
    fun `two workers bound preparation and transfers across collection batches`() = runTest {
        val backend = FakeBackend()
        val queue = PostDownloadQueue(backgroundScope, backend)
        queue.submit("First", (1..4).map { request(it) })
        queue.submit("Second", listOf(request(5)))
        runCurrent()

        assertEquals(listOf("1", "2"), backend.prepared)
        assertEquals(2, queue.batches.value.flatMap { it.items }.count { it.downloadId != null })
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("1", "2", "3"), backend.prepared)
        assertEquals(1, queue.batches.value.first().savedCount)
    }

    @Test
    fun `cancelling a batch cancels its transfers and unstarted work while preserving successes`() = runTest {
        val backend = FakeBackend()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        val queue = PostDownloadQueue(backgroundScope, backend)
        val id = requireNotNull(queue.submit("Collection", (1..5).map { request(it) }))
        runCurrent()

        queue.cancel(id)
        runCurrent()

        val batch = queue.batches.value.single()
        assertEquals(1, batch.savedCount)
        assertFalse(batch.active)
        assertEquals(setOf(2L, 3L), backend.cancelled.toSet())
        assertEquals(listOf("1", "2", "3"), backend.prepared)
        assertEquals(4, batch.items.count { it.status == PostDownloadStatus.CANCELLED })
    }

    @Test
    fun `retry replaces failed transfer only and never downloads successful posts again`() = runTest {
        val backend = FakeBackend()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        backend.states[2] = DownloadTransferSnapshot(PostDownloadStatus.FAILED, message = "Not enough storage")
        val queue = PostDownloadQueue(backgroundScope, backend)
        val id = requireNotNull(queue.submit("Collection", listOf(request(1), request(2))))
        runCurrent()
        assertEquals(1, queue.batches.value.single().failedCount)

        queue.retryFailed(id)
        queue.retryFailed(id)
        runCurrent()

        assertEquals(listOf("1", "2", "2"), backend.prepared)
        assertEquals(listOf(2L), backend.cancelled)
        assertEquals(1L, queue.batches.value.single().items.first().downloadId)
        assertEquals(3L, queue.batches.value.single().items.last().downloadId)
        assertEquals(1, queue.batches.value.single().savedCount)
    }

    @Test
    fun `animation export is part of bounded work and preparation failure remains retryable`() = runTest {
        val release = CompletableDeferred<Unit>()
        val backend = FakeBackend()
        var exportFinished = false
        backend.prepareOverride = { request ->
            when (request.post.id.sourcePostId) {
                "1" -> {
                    release.await()
                    exportFinished = true
                    PreparedPostDownload.Saved
                }
                else -> throw DownloadPreparationException("Connect to Wi-Fi before exporting animation")
            }
        }
        val queue = PostDownloadQueue(backgroundScope, backend, concurrency = 1)
        queue.submit("Animations", listOf(request(1), request(2)))
        runCurrent()
        assertEquals(listOf("1"), backend.prepared)
        assertFalse(exportFinished)

        release.complete(Unit)
        runCurrent()

        assertTrue(exportFinished)
        assertEquals(1, queue.batches.value.single().savedCount)
        assertEquals(1, queue.batches.value.single().failedCount)
        assertEquals("Connect to Wi-Fi before exporting animation", queue.batches.value.single().items.last().message)
    }

    @Test
    fun `cancel during media preparation does not enqueue a system transfer`() = runTest {
        val backend = FakeBackend()
        backend.prepareOverride = {
            CompletableDeferred<Unit>().await()
            PreparedPostDownload.Transfer { error("Must not enqueue cancelled preparation") }
        }
        val queue = PostDownloadQueue(backgroundScope, backend)
        val id = requireNotNull(queue.submit("Collection", listOf(request(1))))
        runCurrent()
        queue.cancel(id)
        runCurrent()

        assertEquals(PostDownloadStatus.CANCELLED, queue.batches.value.single().items.single().status)
        assertTrue(backend.cancelled.isEmpty())
    }

    @Test
    fun `cancel before workers start frees their slots for later batches`() = runTest {
        val backend = FakeBackend()
        val queue = PostDownloadQueue(backgroundScope, backend)
        val cancelled = requireNotNull(queue.submit("Cancelled", listOf(request(1), request(2))))
        queue.cancel(cancelled)
        queue.submit("Next", listOf(request(3)))
        runCurrent()

        assertEquals(listOf("3"), backend.prepared)
        assertFalse(queue.batches.value.first().active)
        assertEquals(PostDownloadStatus.DOWNLOADING, queue.batches.value.last().items.single().status)
    }

    @Test
    fun `reattaching UI observers retains transfer ids progress and selected gallery page`() = runTest {
        val backend = FakeBackend()
        val queue = PostDownloadQueue(backgroundScope, backend)
        val selected = request(1).copy(selectedMedia = SelectedDownloadMedia(MEDIA, pageIndex = 3, totalPages = 10))
        val firstObserver = backgroundScope.launch { queue.batches.collect() }
        queue.submit("Page", listOf(selected, selected))
        runCurrent()
        firstObserver.cancel()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING, 512, 1_024)
        advanceTimeBy(1_000)
        runCurrent()
        backgroundScope.launch { queue.batches.collect() }
        runCurrent()

        val item = queue.batches.value.single().items.single()
        assertEquals(1L, item.downloadId)
        assertEquals(512L, item.downloadedBytes)
        assertEquals(1_024L, item.totalBytes)
        assertEquals(3, backend.requests.single().selectedMedia?.pageIndex)
        assertEquals(1, backend.prepared.size)
    }

    @Test
    fun `empty submissions do not create jobs and failed enqueues become retryable`() = runTest {
        val backend = FakeBackend()
        backend.prepareOverride = { PreparedPostDownload.Transfer { null } }
        val queue = PostDownloadQueue(backgroundScope, backend)
        assertNull(queue.submit("Empty", emptyList()))
        queue.submit("One", listOf(request(1)))
        runCurrent()
        assertEquals(PostDownloadStatus.FAILED, queue.batches.value.single().items.single().status)
    }

    @Test
    fun `an unexpected preparation failure reaches diagnostics with its cause`() = runTest {
        val backend = FakeBackend()
        val cause = IllegalStateException("decoder crashed")
        backend.prepareOverride = { throw cause }
        val reported = mutableListOf<Throwable?>()
        val queue = PostDownloadQueue(backgroundScope, backend, diagnostics = { _, _, error -> reported += error })
        queue.submit("One", listOf(request(1)))
        runCurrent()

        val item = queue.batches.value.single().items.single()
        assertEquals(PostDownloadStatus.FAILED, item.status)
        assertEquals("Could not save this post. Try again.", item.message)
        assertEquals(listOf<Throwable?>(cause), reported)
    }

    @Test
    fun `system progress keeps unknown totals indeterminate and provides actionable failures`() {
        val pending = downloadTransferSnapshot(DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_QUEUED_FOR_WIFI, -1, -1)
        assertEquals(PostDownloadStatus.WAITING, pending.status)
        assertEquals("Waiting for Wi-Fi", pending.message)
        assertNull(pending.totalBytes)
        assertEquals(0L, pending.downloadedBytes)
        val failed = downloadTransferSnapshot(DownloadManager.STATUS_FAILED, DownloadManager.ERROR_INSUFFICIENT_SPACE, 10, 20)
        assertEquals("Not enough storage. Free up space and retry.", failed.message)
        val saved = downloadTransferSnapshot(DownloadManager.STATUS_SUCCESSFUL, 0, 20, 20)
        assertEquals(PostDownloadStatus.SAVED, saved.status)
        assertEquals(20L, saved.totalBytes)
    }

    @Test
    fun `gallery expansion shares the worker bound and retries only failed pages`() = runTest {
        val backend = FakeBackend()
        var transfer = 0L
        backend.prepareOverride = { request ->
            if (request.selectedMedia == null) {
                PreparedPostDownload.Gallery(galleryDownloadPages(request.post))
            } else PreparedPostDownload.Transfer { ++transfer }
        }
        val gallery = request(1).post.copy(media = List(3) { MEDIA.copy(url = "https://example.test/$it.jpg") }, mediaCount = 3)
        val queue = PostDownloadQueue(backgroundScope, backend)
        val batchId = requireNotNull(queue.submit("Gallery", listOf(PostDownloadRequest(gallery, originLabel = "Codex · Favorites"))))
        runCurrent()
        assertEquals(3, queue.batches.value.single().items.size)
        assertTrue(queue.batches.value.single().items.all { it.request.originLabel == "Codex · Favorites" })
        assertEquals(listOf(null, 0, 1), backend.requests.map { it.selectedMedia?.pageIndex })
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        backend.states[2] = DownloadTransferSnapshot(PostDownloadStatus.FAILED)
        advanceTimeBy(1_000)
        runCurrent()
        queue.retryFailed(batchId)
        runCurrent()
        assertEquals(listOf(null, 0, 1, 2, 1), backend.requests.map { it.selectedMedia?.pageIndex })
        assertEquals(1, queue.batches.value.single().savedCount)
        queue.cancel(batchId)
        runCurrent()
        assertEquals(1, queue.batches.value.single().savedCount)
        assertFalse(queue.batches.value.single().active)
    }

    @Test
    fun `an existing canonical download is terminal and does not offer a failing retry`() = runTest {
        val backend = FakeBackend()
        backend.states[1] = downloadTransferSnapshot(DownloadManager.STATUS_FAILED,
            DownloadManager.ERROR_FILE_ALREADY_EXISTS, 0, -1)
        val queue = PostDownloadQueue(backgroundScope, backend)
        val batchId = requireNotNull(queue.submit("Existing", listOf(request(1))))
        runCurrent()
        val batch = queue.batches.value.single()
        assertEquals(1, batch.existingCount)
        assertEquals(0, batch.failedCount)
        assertFalse(batch.active)
        queue.retryFailed(batchId)
        runCurrent()
        assertEquals(1, backend.prepared.size)
        assertTrue(backend.cancelled.isEmpty())
    }

    @Test
    fun `history clearing stops active work but keeps completed destinations and is durable`() = runTest {
        val backend = FakeBackend()
        backend.states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
        var persisted = emptyList<PostDownloadBatch>()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = persisted
            override suspend fun save(batches: List<PostDownloadBatch>) { persisted = batches }
        }
        val queue = PostDownloadQueue(backgroundScope, backend, jobStore = store)
        queue.submit("Mixed", listOf(request(1), request(2), request(3)))
        runCurrent()
        val items = queue.batches.value.single().items
        queue.removeItems(items.mapTo(mutableSetOf()) { it.id }, deleteFiles = false)
        runCurrent()

        assertTrue(queue.batches.value.isEmpty())
        assertTrue(persisted.isEmpty())
        assertTrue(backend.deleted.isEmpty())
        assertEquals(PostDownloadStatus.SAVED, backend.states.getValue(1).status)
        val prepared = backend.prepared.toList()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(prepared, backend.prepared)
    }

    @Test
    fun `bulk deletion removes successes and retains failed file deletion for retry`() = runTest {
        val backend = FakeBackend().apply {
            states[1] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            states[2] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            failDeletePost = "2"
        }
        val queue = PostDownloadQueue(backgroundScope, backend)
        queue.submit("Saved", listOf(request(1), request(2)))
        runCurrent()
        val ids = queue.batches.value.single().items.mapTo(mutableSetOf()) { it.id }
        queue.removeItems(ids, deleteFiles = true)
        val failed = queue.batches.value.single().items.single()
        assertEquals("2", failed.request.post.id.sourcePostId)
        assertEquals(PostDownloadStatus.SAVED, failed.status)
        assertTrue(failed.message.orEmpty().contains("Could not remove"))
        assertEquals(listOf("1"), backend.deleted)
        backend.failDeletePost = null
        queue.removeItems(setOf(failed.id), deleteFiles = true)
        assertTrue(queue.batches.value.isEmpty())
        assertEquals(listOf("1", "2"), backend.deleted)
    }

    @Test
    fun `clearing during preparation waits for cancellation before removing its record`() = runTest {
        val backend = FakeBackend()
        var departed = false
        backend.prepareOverride = {
            try { kotlinx.coroutines.awaitCancellation() } finally { departed = true }
        }
        val queue = PostDownloadQueue(backgroundScope, backend)
        queue.submit("Preparing", listOf(request(1)))
        runCurrent()
        val id = queue.batches.value.single().items.single().id
        queue.removeItems(setOf(id), deleteFiles = false)
        assertTrue(departed)
        assertTrue(queue.batches.value.isEmpty())
    }

    @Test
    fun `a removal confirmation for an expanded gallery still removes its page downloads`() = runTest {
        val backend = FakeBackend()
        backend.prepareOverride = { request ->
            if (request.selectedMedia == null) PreparedPostDownload.Gallery(galleryDownloadPages(request.post))
            else PreparedPostDownload.Saved
        }
        val gallery = request(1).post.copy(media = List(3) { MEDIA }, mediaCount = 3)
        val queue = PostDownloadQueue(backgroundScope, backend)
        queue.submit("Gallery", listOf(PostDownloadRequest(gallery)))
        val originalId = queue.batches.value.single().items.single().id
        runCurrent()
        assertEquals(3, queue.batches.value.single().items.size)
        assertTrue(queue.removeItems(setOf(originalId), deleteFiles = true))
        assertTrue(queue.batches.value.isEmpty())
        assertEquals(3, backend.deleted.size)
    }

    @Test
    fun `removing an expanded gallery reports failure when one of its pages stays`() = runTest {
        val backend = FakeBackend()
        backend.prepareOverride = { request ->
            if (request.selectedMedia == null) PreparedPostDownload.Gallery(galleryDownloadPages(request.post))
            else PreparedPostDownload.Saved
        }
        backend.failDeletePage = 1
        val queue = PostDownloadQueue(backgroundScope, backend)
        queue.submit("Gallery", listOf(PostDownloadRequest(request(1).post.copy(media = List(3) { MEDIA }, mediaCount = 3))))
        val originalId = queue.batches.value.single().items.single().id
        runCurrent()

        assertFalse(queue.removeItems(setOf(originalId), deleteFiles = true))
        assertEquals(listOf(1), queue.batches.value.single().items.map { it.request.selectedMedia?.pageIndex })
    }

    @Test
    fun `restored work shows at once but runs only once the start gate opens`() = runTest {
        val backend = FakeBackend()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Saved", listOf(PostDownloadItem(2, request(1)))))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val sourcesReady = CompletableDeferred<Unit>()
        val queue = PostDownloadQueue(backgroundScope, backend, jobStore = store, startGate = sourcesReady::await)

        queue.restore()
        runCurrent()
        assertEquals(PostDownloadStatus.QUEUED, queue.batches.value.single().items.single().status)
        assertTrue(backend.prepared.isEmpty())

        sourcesReady.complete(Unit)
        runCurrent()
        assertEquals(listOf("1"), backend.prepared)
    }

    @Test
    fun `clips encode one at a time with progress and end saved with their note (ADR 0223)`() = runTest {
        val backend = FakeBackend()
        val finish = CompletableDeferred<Unit>()
        backend.prepareOverride = { request ->
            if (request.clip == null) PreparedPostDownload.Transfer { 99L }
            else PreparedPostDownload.Encode { progress -> progress(40); finish.await(); "Saved without subtitles" }
        }
        val queue = PostDownloadQueue(backgroundScope, backend)
        queue.submit("Clip", listOf(clip(1), clip(2)))
        queue.submit("Post", listOf(request(3)))
        runCurrent()

        assertEquals(listOf(PostDownloadStatus.ENCODING to 40, PostDownloadStatus.QUEUED to null, PostDownloadStatus.DOWNLOADING to null),
            queue.batches.value.flatMap { it.items }.map { it.status to it.encodedPercent })
        finish.complete(Unit)
        runCurrent()

        assertEquals(listOf(PostDownloadStatus.SAVED, PostDownloadStatus.SAVED), queue.batches.value.first().items.map { it.status })
        assertEquals("Saved without subtitles", queue.batches.value.first().items.first().message)
    }

    @Test
    fun `a failed clip shows its own message and reaches diagnostics with the encoder's error (ADR 0223)`() = runTest {
        val backend = FakeBackend()
        val cause = IllegalStateException("decoder rejected its input")
        backend.prepareOverride = { PreparedPostDownload.Encode { throw DownloadPreparationException("This episode can't be clipped on this device.", cause) } }
        val reported = mutableListOf<Throwable?>()
        val queue = PostDownloadQueue(backgroundScope, backend, diagnostics = { _, _, error -> reported += error })
        queue.submit("Clip", listOf(clip(1)))
        runCurrent()

        val item = queue.batches.value.single().items.single()
        assertEquals(PostDownloadStatus.FAILED to "This episode can't be clipped on this device.", item.status to item.message)
        assertEquals(listOf<Throwable?>(cause), reported)
    }

    @Test
    fun `cancelling a clip mid-encode ends it cancelled, and an interrupted clip is never retried (ADR 0223)`() = runTest {
        val backend = FakeBackend()
        backend.prepareOverride = { PreparedPostDownload.Encode { kotlinx.coroutines.awaitCancellation() } }
        val queue = PostDownloadQueue(backgroundScope, backend)
        val id = requireNotNull(queue.submit("Clip", listOf(clip(1))))
        runCurrent()
        queue.cancel(id)
        runCurrent()

        assertEquals(PostDownloadStatus.CANCELLED, queue.batches.value.single().items.single().status)
        val interrupted = PostDownloadItem(1, clip(1).let { it.copy(clip = it.clip?.copy(stream = null)) }, PostDownloadStatus.FAILED)
        assertFalse(interrupted.retryable)
        assertTrue(PostDownloadItem(2, clip(2), PostDownloadStatus.FAILED).retryable)
    }

    private fun clip(id: Int) = request(id).copy(clip = EpisodeClipRequest(
        com.theoriacodex.app.viewer.EpisodeClipRange(1_000L, 4_000L),
        com.theoriacodex.domain.model.MediaStream("https://example.test/$id.m3u8", "Main"), com.theoriacodex.app.viewer.EpisodeClipTracks(subtitles = true, subtitleIndex = 0),
    ))

    private class FakeBackend : PostDownloadBackend {
        val deleted = mutableListOf<String>()
        var failDeletePost: String? = null
        var failDeletePage: Int? = null
        val prepared = mutableListOf<String>()
        val requests = mutableListOf<PostDownloadRequest>()
        val cancelled = mutableListOf<Long>()
        val states = mutableMapOf<Long, DownloadTransferSnapshot>()
        var prepareOverride: (suspend (PostDownloadRequest) -> PreparedPostDownload)? = null
        private var nextId = 0L

        override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
            prepared += request.post.id.sourcePostId
            requests += request
            return prepareOverride?.invoke(request) ?: PreparedPostDownload.Transfer { ++nextId }
        }

        override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot =
            states[downloadId] ?: DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING)

        override suspend fun cancel(downloadId: Long) { cancelled += downloadId }
        override suspend fun deleteFile(item: PostDownloadItem) {
            val postId = item.request.post.id.sourcePostId
            if (postId == failDeletePost || failDeletePage != null && item.request.selectedMedia?.pageIndex == failDeletePage) {
                throw java.io.IOException("File access denied")
            }
            deleted += postId
        }
    }

    private fun request(id: Int) = PostDownloadRequest(
        Post(PostId(SourceKey.PIXIV, "$id"), MEDIA, MEDIA, listOf(MEDIA), null, null, null,
            emptyList(), emptyList(), null, null, title = "Post $id"),
    )
}

private val MEDIA = ImageRef("https://example.com/full.jpg", null, "image/jpeg")
