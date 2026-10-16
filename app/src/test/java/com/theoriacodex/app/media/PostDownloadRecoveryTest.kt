package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
    @Test fun `cancelling the removal checkpoint restores history and permits a later retry`() = runTest {
        val original = listOf(PostDownloadBatch(1, "Saved", listOf(PostDownloadItem(2,
            PostDownloadRequest(testPost()), PostDownloadStatus.SAVED))))
        val savingRemoval = CompletableDeferred<Unit>()
        var stored = original
        var suspendRemoval = true
        val store = object : PostDownloadJobStore {
            override suspend fun load() = stored
            override suspend fun save(batches: List<PostDownloadBatch>) {
                if (batches.isEmpty() && suspendRemoval) {
                    savingRemoval.complete(Unit)
                    awaitCancellation()
                }
                stored = batches
            }
        }
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("Already saved")
            override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot = error("No native transfer")
            override suspend fun cancel(downloadId: Long) = error("No native transfer")
            override suspend fun deleteFile(item: PostDownloadItem) = error("History removal must keep files")
        }
        val queue = PostDownloadQueue(backgroundScope, backend, jobStore = store)
        val removal = launch { queue.removeItems(setOf(2L), deleteFiles = false) }
        savingRemoval.await()
        assertTrue(queue.batches.value.isEmpty())
        removal.cancelAndJoin()
        assertTrue(removal.isCancelled)
        assertEquals(original, queue.batches.value)
        assertEquals(original, stored)
        suspendRemoval = false
        queue.removeItems(setOf(2L), deleteFiles = false)
        assertTrue(queue.batches.value.isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test fun `removal reconciles a native enqueue gap and does not drop an unidentified transfer`() = runTest {
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Gap", listOf(PostDownloadItem(2,
                PostDownloadRequest(testPost(), restored = true), PostDownloadStatus.FAILED, enqueuePending = true))))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        var found: Long? = null
        var deleted: Long? = null
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("No duplicate transfer")
            override suspend fun findTransfer(marker: String) = found
            override suspend fun snapshot(downloadId: Long) = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            override suspend fun cancel(downloadId: Long) = Unit
            override suspend fun deleteFile(item: PostDownloadItem) { deleted = item.downloadId }
        }
        val queue = PostDownloadQueue(backgroundScope, backend, jobStore = store)
        queue.removeItems(setOf(2L), deleteFiles = true)
        assertEquals(1, queue.batches.value.single().items.size)
        assertEquals(null, deleted)
        found = 99L
        queue.removeItems(setOf(2L), deleteFiles = true)
        assertEquals(99L, deleted)
        assertTrue(queue.batches.value.isEmpty())
    }

    @Test fun `origin metadata round trips while legacy jobs use a neutral origin`() = runTest {
        val directory = Files.createTempDirectory("download-origins").toFile()
        try {
            val file = directory.resolve("queue.json")
            val store = FileBackedPostDownloadJobStore(file)
            store.save(listOf(PostDownloadBatch(1, "Save", listOf(PostDownloadItem(2,
                PostDownloadRequest(testPost(), originLabel = "Codex · Favorites"), PostDownloadStatus.SAVED)))))
            assertEquals("Codex · Favorites", store.load().single().items.single().request.originLabel)
            val legacy = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            legacy.addProperty("version", 2)
            legacy.getAsJsonArray("batches")[0].asJsonObject.getAsJsonArray("items")[0].asJsonObject.remove("origin")
            file.writeText(legacy.toString())
            assertEquals("Downloads", store.load().single().items.single().request.originLabel)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `retry keeps a completed export identity across a failed acknowledgement and process restart`() = runTest {
        val directory = Files.createTempDirectory("export-recovery").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val marker = "stable-export"
        val encoded = mutableSetOf<String>()
        val markers = mutableListOf<String>()
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
                markers += request.marker
                if (encoded.add(request.marker)) throw java.io.IOException("Published before acknowledgement")
                return PreparedPostDownload.Saved
            }
            override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot = error("Not a native download")
            override suspend fun cancel(downloadId: Long) = Unit
        }
        try {
            val store = FileBackedPostDownloadJobStore(directory.resolve("queue.json"))
            val first = PostDownloadQueue(firstScope, backend, jobStore = store)
            first.submit("Animation", listOf(PostDownloadRequest(testPost(), marker = marker)))
            first.batches.first { it.single().failedCount == 1 }
            while (store.load().single().failedCount != 1) kotlinx.coroutines.yield()
            firstScope.cancel()
            val reopened = PostDownloadQueue(secondScope, backend, jobStore = store)
            reopened.restore()
            reopened.retryFailed(reopened.batches.value.single().id)
            reopened.batches.first { it.single().savedCount == 1 }
            assertEquals(listOf(marker, marker), markers)
            assertEquals(setOf(marker), encoded)
        } finally { firstScope.cancel(); secondScope.cancel(); directory.deleteRecursively() }
    }

    @Test fun `legacy interrupted preparation remains explicitly ambiguous after migration and resave`() = runTest {
        val directory = Files.createTempDirectory("legacy-export").toFile()
        try {
            val file = directory.resolve("queue.json")
            val store = FileBackedPostDownloadJobStore(file)
            val batch = PostDownloadBatch(1, "Old", listOf(PostDownloadItem(2, PostDownloadRequest(testPost()),
                PostDownloadStatus.PREPARING)))
            store.save(listOf(batch))
            assertFalse(store.load().single().items.single().request.untrackedPreparation)
            val legacy = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
            legacy.addProperty("version", 1)
            file.writeText(legacy.toString())
            val restored = store.load()
            assertTrue(restored.single().items.single().request.untrackedPreparation)
            store.save(restored)
            assertTrue(store.load().single().items.single().request.untrackedPreparation)
        } finally { directory.deleteRecursively() }
    }

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

    @Test fun `retry reconciles an enqueue gap and never duplicates an unidentified native transfer`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var found: Long? = null
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Gap", listOf(PostDownloadItem(2,
                PostDownloadRequest(testPost(), restored = true), PostDownloadStatus.FAILED, enqueuePending = true))))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("No duplicate")
            override suspend fun findTransfer(marker: String) = found
            override suspend fun snapshot(downloadId: Long) = DownloadTransferSnapshot(PostDownloadStatus.SAVED)
            override suspend fun cancel(downloadId: Long) = error("Completed file is protected")
        }
        try {
            val queue = PostDownloadQueue(scope, backend, jobStore = store)
            queue.retryFailed(1)
            assertEquals(PostDownloadStatus.FAILED, queue.batches.value.single().items.single().status)
            found = 99L
            queue.retryFailed(1)
            assertEquals(PostDownloadStatus.SAVED, queue.batches.value.single().items.single().status)
            assertEquals(99L, queue.batches.value.single().items.single().downloadId)
        } finally { scope.cancel() }
    }

    @Test fun `a transient restore failure can be retried in the same owner`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var first = true
        val store = object : PostDownloadJobStore {
            override suspend fun load(): List<PostDownloadBatch> {
                if (first) { first = false; throw java.io.IOException("temporarily unreadable") }
                return emptyList()
            }
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("Nothing queued")
            override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot = error("Nothing queued")
            override suspend fun cancel(downloadId: Long) = Unit
        }
        try {
            val queue = PostDownloadQueue(scope, backend, jobStore = store)
            assertTrue(runCatching { queue.restore() }.isFailure)
            queue.restore()
            assertTrue(queue.batches.value.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `a restored cancelled enqueue gap reconciles its native transfer before returning`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cancelled = mutableListOf<Long>()
        val store = object : PostDownloadJobStore {
            override suspend fun load() = listOf(PostDownloadBatch(1, "Cancelled", listOf(PostDownloadItem(2,
                PostDownloadRequest(testPost()), PostDownloadStatus.CANCELLED, enqueuePending = true))))
            override suspend fun save(batches: List<PostDownloadBatch>) = Unit
        }
        val backend = object : PostDownloadBackend {
            override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload = error("Cancelled")
            override suspend fun findTransfer(marker: String) = 99L
            override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot = error("Cancelled")
            override suspend fun cancel(downloadId: Long) { cancelled += downloadId }
        }
        try {
            val queue = PostDownloadQueue(scope, backend, jobStore = store)
            queue.restore()
            assertEquals(listOf(99L), cancelled)
            assertEquals(PostDownloadStatus.CANCELLED, queue.batches.value.single().items.single().status)
        } finally { scope.cancel() }
    }

}
