package com.theoriacodex.app.media

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CacheSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostDownloadsViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `a removal committed after the Activity finished still saves the queue`() = runBlocking {
        val store = FileBackedPostDownloadJobStore(File(context.filesDir, "theoria_codex/download_jobs.json"))
        store.save(listOf(PostDownloadBatch(1, "Saved", listOf(
            PostDownloadItem(2, PostDownloadRequest(testPost()), PostDownloadStatus.SAVED),
            PostDownloadItem(3, PostDownloadRequest(testPost()), PostDownloadStatus.SAVED),
        ))))
        val owner = ViewModelStore()
        val downloads = ViewModelProvider.create(owner, viewModelFactory {
            initializer { PostDownloadsViewModel(context, { CacheSettings() }, { it }, { _, _ -> Result.success(Unit) }) }
        })[PostDownloadsViewModel::class]
        owner.clear()

        assertTrue(downloads.removeNow(setOf(2), deleteFiles = false))
        assertEquals(listOf(3L), store.load().flatMap { batch -> batch.items.map(PostDownloadItem::id) })
    }
}
