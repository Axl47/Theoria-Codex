package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostDownloadRecoveryTest {
    @Test fun `a reopened queue reconciles completed and active native transfers before continuing gallery pages`() = runTest {
        val directory = Files.createTempDirectory("download-recovery").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val file = directory.resolve("queue.json")
            val store = FileBackedPostDownloadJobStore(file)
            val gallery = testPost().copy(media = (0..2).map {
                ImageRef("https://example.test/$it.jpg?secret=never-persist", "/private/device/path", "image/jpeg")
            }, mediaCount = 3)
            var next = 0L
            val preparedPages = mutableListOf<Int?>()
            val native = mutableMapOf<Long, DownloadTransferSnapshot>()
            val backend = object : PostDownloadBackend {
                override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
                    preparedPages += request.selectedMedia?.pageIndex
                    if (request.selectedMedia == null) return PreparedPostDownload.Gallery(galleryDownloadPages(gallery))
                    return PreparedPostDownload.Transfer {
                        (++next).also { native[it] = DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING) }
                    }
                }
                override suspend fun snapshot(downloadId: Long) = native.getValue(downloadId)
                override suspend fun cancel(downloadId: Long) { error("Existing successful/active transfers must not be replaced") }
            }
            val first = PostDownloadQueue(firstScope, backend, pollIntervalMs = 10, jobStore = store)
            first.submit("Gallery", listOf(PostDownloadRequest(gallery)))
            first.batches.first { it.single().items.count { item -> item.downloadId != null } == 2 }
            // Wait for the enqueue checkpoint before simulating departure.
            while (store.load().single().items.count { it.downloadId != null } < 2) kotlinx.coroutines.yield()
            assertFalse(file.readText().contains("never-persist"))
            assertFalse(file.readText().contains("/private/device/path"))
            native[1L] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            firstScope.cancel()
            val reopened = PostDownloadQueue(secondScope, backend, pollIntervalMs = 10, jobStore = store)
            reopened.restore()
            reopened.batches.first { it.single().items.any { item -> item.downloadId == 3L } }
            assertEquals(listOf(null, 0, 1, 2), preparedPages)
            native[2L] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            native[3L] = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            val completed = reopened.batches.first { !it.single().active }.single()
            assertEquals(3, completed.savedCount)
            assertEquals(3L, next)
        } finally { firstScope.cancel(); secondScope.cancel(); directory.deleteRecursively() }
    }

    @Test fun `a persisted enqueue gap resumes its native marker without preparing or duplicating a transfer`() = runTest {
        val directory = Files.createTempDirectory("download-marker").toFile()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val store = FileBackedPostDownloadJobStore(directory.resolve("queue.json"))
            val request = PostDownloadRequest(testPost())
            store.save(listOf(PostDownloadBatch(1, "Gap", listOf(PostDownloadItem(2, request,
                PostDownloadStatus.PREPARING, enqueuePending = true)))))
            val backend = object : PostDownloadBackend {
                override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("Must reconcile")
                override suspend fun findTransfer(marker: String): Long? { assertEquals(request.marker, marker); return 99L }
                override suspend fun snapshot(downloadId: Long) = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
                override suspend fun cancel(downloadId: Long) = error("Completed file is protected")
            }
            val queue = PostDownloadQueue(scope, backend, jobStore = store)
            queue.restore()
            val batch = queue.batches.first { !it.single().active }.single()
            assertEquals(1, batch.savedCount)
            assertEquals(99L, batch.items.single().downloadId)
            assertTrue(batch.items.single().request.restored)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
    @Test fun `cancelling a restored batch also cancels transfers awaiting a worker slot`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cancelled = mutableListOf<Long>()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Restored", (2L..4L).map {
                PostDownloadItem(it, PostDownloadRequest(testPost(), restored = true), downloadId = it + 10)
            }))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("Must reconcile")
            override suspend fun snapshot(downloadId: Long) = DownloadTransferSnapshot(PostDownloadStatus.DOWNLOADING)
            override suspend fun cancel(downloadId: Long) { cancelled += downloadId }
        }
        try {
            val queue = PostDownloadQueue(scope, backend, concurrency = 1, jobStore = store)
            queue.restore()
            queue.cancel(1)
            assertTrue(cancelled.containsAll(listOf(12L, 13L, 14L)))
            assertTrue(queue.batches.value.single().items.all { it.status == PostDownloadStatus.CANCELLED })
        } finally { scope.cancel() }
    }

    @Test fun `a failed admission checkpoint cannot start a native download`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = object : PostDownloadJobStore {
            override suspend fun load() = emptyList<PostDownloadBatch>()
            override suspend fun save(batches: List<PostDownloadBatch>): Unit = throw java.io.IOException("disk full")
        }
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("Unadmitted download")
            override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot = error("Unadmitted download")
            override suspend fun cancel(downloadId: Long) = Unit
        }
        try {
            val queue = PostDownloadQueue(scope, backend, jobStore = store)
            val result = runCatching { queue.submit("Save", listOf(PostDownloadRequest(testPost()))) }
            assertTrue(result.exceptionOrNull() is java.io.IOException)
            assertTrue(queue.batches.value.isEmpty())
        } finally { scope.cancel() }
    }

}
