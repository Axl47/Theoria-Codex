package com.theoriacodex.app.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Two instrumentation launches with a host force-stop between them; no fake DownloadManager. */
@RunWith(AndroidJUnit4::class)
class PostDownloadProcessRecoveryDeviceTest {
    @Test fun nativeGalleryResumesAcrossProcessDeath() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        val selectedPhase = arguments.getString("theoriaRecoveryPhase")
        assumeTrue("Process recovery requires explicit two-phase host orchestration", selectedPhase in setOf("write", "read"))
        val token = requireNotNull(arguments.getString("theoriaRecoveryToken"))
        require(token.matches(Regex("[a-z0-9]{12,40}")))
        val phase = requireNotNull(selectedPhase)
        val baseUrl = requireNotNull(arguments.getString("theoriaRecoveryUrl"))
        require(baseUrl.startsWith("http://127.0.0.1:"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.theoriacodex.debug")
        val root = File(context.cacheDir, "native-recovery-$token").apply { mkdirs() }
        val destination = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
            "native-recovery-$token").apply { mkdirs() }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val media = (0..3).map { ImageRef("$baseUrl/$it.png", null, "image/png") }
        val gallery = Post(PostId(SourceKey.PIXIV, "native-$token"), media.first(), media.first(), media = media,
            pageUrl = null, width = null, height = null, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null, title = "Recovery fixture", mediaCount = 4)
        val admittedGap = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val store = FileBackedPostDownloadJobStore(root.resolve("queue.json"))
        val backend = NativeFixtureBackend(manager, destination, gallery,
            afterAdmission = { page ->
                if (phase == "write" && page == 1) {
                    admittedGap.complete(Unit)
                    // Pin the actual native-enqueue/ID-checkpoint gap until this process is killed.
                    CountDownLatch(1).await()
                }
            })
        val queue = PostDownloadQueue(scope, backend, concurrency = 1, pollIntervalMs = 50, jobStore = store)
        if (phase == "write") {
            queue.submit("Process recovery", listOf(PostDownloadRequest(gallery)))
            withTimeout(30_000) { admittedGap.await() }
            val checkpoint = store.load().single()
            assertEquals(PostDownloadStatus.SAVED, checkpoint.items[0].status)
            assertTrue(checkpoint.items[1].enqueuePending)
            assertEquals(null, checkpoint.items[1].downloadId)
            assertTrue(checkpoint.items.drop(2).all { it.status == PostDownloadStatus.QUEUED })
            assertArrayEquals(expectedBytes(0), destination.resolve("0.png").readBytes())
            val gapId = requireNotNull(backend.findTransfer(checkpoint.items[1].request.marker))
            root.resolve("proof.json").writeText(JSONObject().put("pid", Process.myPid())
                .put("completedId", checkpoint.items[0].downloadId).put("gapId", gapId).toString())
            // Do not cancel the pinned admission: the host kills this exact isolated process.
        } else {
            require(phase == "read")
            val proof = JSONObject(root.resolve("proof.json").readText())
            assertNotEquals(proof.getInt("pid"), Process.myPid())
            try {
                queue.restore()
                val completed = withTimeout(40_000) { queue.batches.first { it.single().savedCount == 4 }.single() }
                assertEquals(proof.getLong("completedId"), completed.items[0].downloadId)
                assertEquals(proof.getLong("gapId"), completed.items[1].downloadId)
                assertEquals(4, completed.items.mapNotNull { it.downloadId }.distinct().size)
                (0..3).forEach { assertArrayEquals(expectedBytes(it), destination.resolve("$it.png").readBytes()) }
                assertEquals(setOf("0.png", "1.png", "2.png", "3.png"), destination.listFiles().orEmpty().map { it.name }.toSet())
                withTimeout(10_000) { while (store.load().single().savedCount != 4) delay(25) }
                // Cleanup is restricted to this test's own native rows and private external directory.
                manager.remove(*completed.items.mapNotNull { it.downloadId }.toLongArray())
                assertTrue(destination.deleteRecursively())
                assertTrue(root.deleteRecursively())
            } finally { scope.cancel() }
        }
    }

    private fun expectedBytes(page: Int) = "native-recovery-page-$page".toByteArray()
}

/** Controlled transport/destination around the production queue and durable store. */
private class NativeFixtureBackend(
    private val manager: DownloadManager,
    private val destination: File,
    private val gallery: Post,
    private val afterAdmission: (Int) -> Unit,
) : PostDownloadBackend {
    override suspend fun prepare(request: PostDownloadRequest): PreparedPostDownload {
        val selected = request.selectedMedia ?: return PreparedPostDownload.Gallery(galleryDownloadPages(gallery))
        val ref = gallery.media[selected.pageIndex]
        return PreparedPostDownload.Transfer {
            val id = manager.enqueue(DownloadManager.Request(Uri.parse(ref.url).buildUpon()
                .fragment(downloadRequestMarker(request.marker)).build())
                .setDestinationUri(Uri.fromFile(destination.resolve("${selected.pageIndex}.png"))))
            afterAdmission(selected.pageIndex)
            id
        }
    }

    override suspend fun findTransfer(marker: String): Long? = manager.query(DownloadManager.Query()).use { cursor ->
        while (cursor.moveToNext()) {
            val uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
            if (uri.fragment == downloadRequestMarker(marker)) return@use cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        }
        null
    }

    override suspend fun snapshot(downloadId: Long): DownloadTransferSnapshot =
        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            check(cursor.moveToFirst())
            downloadTransferSnapshot(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
        }

    override suspend fun cancel(downloadId: Long) {
        if (snapshot(downloadId).status !in setOf(PostDownloadStatus.SAVED, PostDownloadStatus.ALREADY_EXISTS)) manager.remove(downloadId)
    }
}
