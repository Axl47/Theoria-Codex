package com.theoriacodex.app.ui.routes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OfflineViewerWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `offline galleries replace remote Codex snapshots for every Viewer page and cold restoration`() = runTest {
        val directory = temporary.newFolder()
        val images = (0..2).map { createPng(directory.resolve("input-$it.png"), Color.CYAN + it) }
        val galleries = listOf("one", "two").map { id -> gallery(id, images) }
        val snapshots = galleries.map { post -> post.copy(
            media = post.media.map { it.copy(localPath = null) }, full = post.full?.copy(localPath = null),
        ) }
        var graph = JourneyAppContainer(ApplicationProvider.getApplicationContext(), directory, galleries.groupBy { it.id.source }, this)
        try {
            graph.awaitReady()
            graph.content.ensureCodex("offline", "Offline galleries")
            graph.content.addItems("offline", snapshots)
            val offline = requireNotNull(graph.features.offlineMedia)
            offline.makeAvailableOffline("codex:offline", snapshots)
            val job = offline.jobs.first { it["codex:offline"]?.isRunning == false }.getValue("codex:offline")
            assertEquals(2, job.completedPosts)
            assertTrue(job.failedPosts.isEmpty())
            assertEquals(6, offline.store.snapshot.value.mediaCount)
            images.forEach { assertTrue(it.delete()) }
            graph.registry.resolveRequests.clear()
            graph.data.cacheRepository.clearThumbnailCache()
            graph.data.cacheRepository.clearFullImageCache()
            val workflow = workflow(graph)
            val launchContext = ViewerLaunchContext("codex:offline", 1, ViewerStreamSource.CODEX, 0)
            val prepared = workflow.preparePostsForLaunch(snapshots, launchContext)
            val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this@runTest)
            owner.replaceSession(ViewerSession(prepared, launchContext, initialMediaIndex = 2))
            assertEquals(galleries[1].id, owner.state.value.currentPage?.post?.id)
            assertLocalGallery(owner.state.value.currentPage!!.post, directory)
            assertEquals(2, owner.state.value.currentPage?.selectedMediaIndex)
            owner.onAction(ViewerAction.SelectPage(0))
            owner.onAction(ViewerAction.SelectMedia(1))
            assertLocalGallery(owner.state.value.currentPage!!.post, directory)
            assertTrue(owner.state.value.currentMedia?.displayLocation?.startsWith(directory.resolve("offline-media").path) == true)
            assertEquals(prepared.first(), workflow.resolvePost(galleries.first().id, ViewerStreamSource.CODEX))
            assertTrue(graph.registry.resolveRequests.isEmpty())

            graph.shutdown()
            graph = JourneyAppContainer(ApplicationProvider.getApplicationContext(), directory, galleries.groupBy { it.id.source }, this)
            graph.awaitReady()
            val restored = requireNotNull(workflow(graph).restoreSession(ViewerRestorationRequest(
                session = ViewerSessionIdentity("offline-session", "codex:offline", ViewerStreamSource.CODEX.name),
                selectedPostId = galleries[1].id, orderedPostIds = galleries.map(Post::id),
                selectedMediaIndex = 2, recentsSection = null,
            )))
            assertEquals(1, restored.context.startIndex)
            assertEquals(2, restored.initialMediaIndex)
            restored.posts.forEach { assertLocalGallery(it, directory) }
            assertTrue(graph.registry.resolveRequests.isEmpty())
        } finally {
            graph.shutdown()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.workflow(graph: JourneyAppContainer) = ViewerRouteWorkflow(
        graph.data, this, graph.sources, { null }, { null }, { null }, offlineMedia = graph.features.offlineMedia,
    )

    private fun assertLocalGallery(post: Post, directory: File) {
        assertEquals(3, post.media.size)
        post.media.forEach { ref ->
            val file = File(requireNotNull(ref.localPath))
            assertTrue(file.path.startsWith(directory.resolve("offline-media").path))
            assertTrue(file.isFile && file.length() > 0)
        }
    }

    private fun gallery(id: String, images: List<File>): Post {
        val media = images.mapIndexed { index, file ->
            ImageRef("https://unavailable.invalid/$id/$index.png", file.absolutePath, "image/png")
        }
        return testPost(SourceKey.PIXIV, id).copy(preview = media.first(), full = media.first(), media = media, mediaCount = media.size)
    }

    private fun createPng(file: File, color: Int): File {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }
}
