package com.theoriacodex.app.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.testing.testPost
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostDownloadPreviewTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `a reopened video download builds its preview from the saved file without a remote URL`() = runTest {
        val directory = Files.createTempDirectory("download-preview").toFile()
        try {
            val store = FileBackedPostDownloadJobStore(directory.resolve("queue.json"))
            store.save(listOf(PostDownloadBatch(1, "Saved", listOf(PostDownloadItem(
                2, PostDownloadRequest(testPost()), PostDownloadStatus.SAVED, downloadId = 42,
            )))))
            val restored = store.load().single().items.single()
            assertNull(restored.request.post.preview.url)
            val file = DownloadedPostFile(Uri.parse("content://downloads/my_downloads/${restored.downloadId}"), "video/mp4")
            val frame = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            frame.eraseColor(Color.BLUE)
            ShadowMediaMetadataRetriever.addScaledFrame(DataSource.toDataSource(context, file.uri), 0, 512, 512, frame)

            val preview = downloadedPostPreview(context, file)
            assertTrue(preview is Bitmap && frame.sameAs(preview))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `local images keep their URI for image decoding`() = runTest {
        val file = DownloadedPostFile(Uri.parse("content://downloads/my_downloads/43"), "image/png")
        assertSame(file.uri, downloadedPostPreview(context, file))
    }

    @Test fun `a video without a decodable frame has no preview`() = runTest {
        val file = DownloadedPostFile(Uri.parse("content://downloads/my_downloads/44"), "video/mp4")
        assertNull(downloadedPostPreview(context, file))
    }
}
