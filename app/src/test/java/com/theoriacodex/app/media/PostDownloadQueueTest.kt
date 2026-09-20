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

    private class FakeBackend : PostDownloadBackend {
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
    }

    private fun request(id: Int) = PostDownloadRequest(
        Post(PostId(SourceKey.PIXIV, "$id"), MEDIA, MEDIA, listOf(MEDIA), null, null, null,
            emptyList(), emptyList(), null, null, title = "Post $id"),
    )
}

private val MEDIA = ImageRef("https://example.com/full.jpg", null, "image/jpeg")
