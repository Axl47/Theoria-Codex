package com.theoriacodex.app.media

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CacheSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostDownloadsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `a removal deletes the rows from the queue file it owns`() = runBlocking {
        val file = File(context.filesDir, "downloads/download_jobs.json")
        val store = FileBackedPostDownloadJobStore(file)
        store.save(listOf(PostDownloadBatch(1, "Saved", listOf(
            PostDownloadItem(2, PostDownloadRequest(testPost()), PostDownloadStatus.SAVED),
            PostDownloadItem(3, PostDownloadRequest(testPost()), PostDownloadStatus.SAVED),
        ))))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val downloads = PostDownloads(context, file, scope, { CacheSettings() }, { it }, { _, _ -> Result.success(Unit) })

            assertTrue(downloads.removeNow(setOf(2), deleteFiles = false))
            assertEquals(listOf(3L), store.load().flatMap { batch -> batch.items.map(PostDownloadItem::id) })
        } finally {
            scope.cancel()
        }
    }
}
