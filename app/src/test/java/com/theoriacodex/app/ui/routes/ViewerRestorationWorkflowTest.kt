package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.media.OfflineMediaCoordinator
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.testViewerOwner
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.OfflineMediaStore
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerRestorationWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `closing the Viewer right after a post shows keeps its statistics and page (ADR 0215)`() = runTest {
        val post = testPost(SourceKey.PIXIV, "closing")
        withGraph(listOf(post)) { graph, workflow ->
            val launch = ViewerSession(listOf(post), context(ViewerStreamSource.RECENTS, "recents:watched"))
            val viewerScope = CoroutineScope(Job())
            val recording = viewerScope.launch(start = CoroutineStart.UNDISPATCHED) { workflow.recordVisiblePost(post, 1, launch) }
            viewerScope.cancel()
            recording.join()

            assertEquals(1L, graph.data.statisticsRepository.observeStatistics().first().watchedPostCount)
            assertEquals(1, graph.data.readingPositions.get(post.id, RecentPostSection.WATCHED)?.mediaNumber)
        }
    }

    @Test
    fun `cold reconstruction preserves filtered order and canonical selection without a live route`() = runTest {
        val posts = (1..3).map { testPost(SourceKey.PIXIV, "$it") }
        withGraph(posts) { graph, workflow ->
            graph.content.ensureCodex("collection", "Collection")
            graph.content.addItems("collection", posts)
            val launch = ViewerSession(
                posts = listOf(posts[2], posts[0]),
                context = context(ViewerStreamSource.CODEX, "codex:collection"),
            )
            val saved = SavedStateHandle()
            val firstOwner = testViewerOwner(saved, scopeOverride = this@runTest)
            firstOwner.replaceSession(launch)
            firstOwner.onAction(ViewerAction.SelectPage(1))
            val coldOwner = testViewerOwner(
                SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                scopeOverride = this@runTest,
            )

            val restored = requireNotNull(workflow.restoreSession(requireNotNull(coldOwner.restorationRequest)))
            coldOwner.replaceSession(restored)

            assertEquals(listOf(posts[2].id, posts[0].id), restored.posts.map(Post::id))
            assertEquals(posts[0].id, coldOwner.state.value.currentPage?.post?.id)
            assertFalse(restored.liveSearchBinding)
            assertEquals(emptyList<Any>(), graph.registry.requests)
        }
    }

    @Test
    fun `missing neighbors never substitute unrelated posts and selected provider post can recover cold search`() = runTest {
        val selected = testPost(SourceKey.PIXIV, "selected")
        val missing = testPost(SourceKey.PIXIV, "missing")
        withGraph(listOf(selected)) { _, workflow ->
            val restored = requireNotNull(workflow.restoreSession(ViewerRestorationRequest(
                session = ViewerSessionIdentity("cold", "old-search", ViewerStreamSource.SEARCH.name),
                selectedPostId = selected.id,
                orderedPostIds = listOf(missing.id, selected.id),
                selectedMediaIndex = 0,
                recentsSection = null,
            )))
            assertEquals(listOf(selected), restored.posts)
            assertEquals(0, restored.context.startIndex)
            assertFalse(restored.liveSearchBinding)
        }
    }

    @Test
    fun `recent gallery resumes last page while highest progress remains monotonic and start over is explicit`() = runTest {
        val gallery = testPost(SourceKey.PIXIV, "gallery").copy(
            media = (1..5).map { ImageRef("https://fixture.invalid/$it.jpg", null, "image/jpeg") },
            mediaCount = 5,
        )
        withGraph(listOf(gallery)) { graph, workflow ->
            val launch = ViewerSession(listOf(gallery), context(ViewerStreamSource.RECENTS, "recents:watched"))
            workflow.recordVisiblePost(gallery, 5, launch)
            workflow.recordVisibleMediaProgress(gallery, 2, launch)
            val resumed = workflow.prepareResumableSession(launch)
            assertEquals(1, resumed.initialMediaIndices[gallery.id])
            assertEquals(5, graph.data.recentsRepository.observeWatchedPosts().first().single().maxViewedMediaNumber)
            val neighbor = testPost(SourceKey.PIXIV, "unwatched-neighbor")
            val neighborLaunch = workflow.prepareResumableSession(launch.copy(posts = listOf(neighbor, gallery)))
            assertEquals(0, neighborLaunch.initialMediaIndices[neighbor.id])
            assertEquals(1, neighborLaunch.initialMediaIndices[gallery.id])
            val viewer = testViewerOwner(SavedStateHandle(), scopeOverride = this@runTest)
            viewer.replaceSession(neighborLaunch)
            viewer.onAction(ViewerAction.SelectPage(1))
            assertEquals(1, viewer.state.value.currentPage?.selectedMediaIndex)
            graph.data.flushPendingWrites()
            val fromDisk = FileBackedReadingPositionRepository(graph.data.storageDirectory)
            assertEquals(2, fromDisk.get(gallery.id, RecentPostSection.WATCHED)?.mediaNumber)

            val restarted = workflow.prepareResumableSession(launch, startOver = true)
            assertEquals(0, restarted.initialMediaIndices[gallery.id])
            assertEquals(5, graph.data.recentsRepository.observeWatchedPosts().first().single().maxViewedMediaNumber)
        }
    }

    @Test
    fun `Search FYP and Recents share last viewed media across entry points`() = runTest {
        val gallery = testPost(SourceKey.PIXIV, "shared-gallery").copy(
            media = (1..5).map { ImageRef("https://fixture.invalid/$it.jpg", null, "image/jpeg") },
            mediaCount = 5,
        )
        withGraph(listOf(gallery)) { graph, workflow ->
            val origins = listOf(ViewerStreamSource.SEARCH, ViewerStreamSource.FOR_YOU, ViewerStreamSource.RECENTS)
            for (origin in origins) {
                val launch = ViewerSession(listOf(gallery), context(origin, "shared"), liveSearchBinding = origin != ViewerStreamSource.RECENTS)
                workflow.recordVisiblePost(gallery, 5, launch)
                workflow.recordVisibleMediaProgress(gallery, 2, launch)
                for (destination in origins) {
                    val target = launch.copy(context = context(destination, "target"))
                    val resumed = workflow.prepareResumableSession(target)
                    assertEquals(1, resumed.initialMediaIndices[gallery.id])
                    assertEquals(target.copy(posts = resumed.posts, initialMediaIndices = mapOf(gallery.id to 1)), resumed)
                }
                val recent = graph.data.recentsRepository.observeWatchedPosts().first().single()
                assertEquals(RecentPostSection.WATCHED, recent.section)
                assertEquals(5, recent.maxViewedMediaNumber)
            }
        }
    }

    @Test
    fun `related shelf restoration stays transient`() = runTest {
        val post = testPost(SourceKey.PIXIV, "related")
        withGraph(listOf(post)) { _, workflow ->
            assertNull(workflow.restoreSession(ViewerRestorationRequest(
                ViewerSessionIdentity("related", "related", ViewerStreamSource.RELATED.name),
                post.id, listOf(post.id), 0, null,
            )))
        }
    }

    private suspend fun TestScope.withGraph(
        posts: List<Post>,
        block: suspend (JourneyAppContainer, ViewerRouteWorkflow) -> Unit,
    ) {
        val graph = JourneyAppContainer(
            ApplicationProvider.getApplicationContext(), temporary.newFolder(),
            posts.groupBy { it.id.source }, this,
        )
        try {
            graph.awaitReady()
            val offlineMedia = OfflineMediaCoordinator(
                OfflineMediaStore(temporary.newFolder(), StandardTestDispatcher(testScheduler)), backgroundScope,
                { it }, { emptyList() }, { _, _, _ -> error("Restoration never saves media offline") },
            )
            val workflow = ViewerRouteWorkflow(
                graph.data.copy(readingPositions = FileBackedReadingPositionRepository(graph.data.storageDirectory)),
                this, graph.sources, { null }, offlineMedia,
                providerDispatcher = StandardTestDispatcher(testScheduler),
            )
            block(graph, workflow)
        } finally {
            graph.shutdown()
        }
    }

    private fun context(source: ViewerStreamSource, query: String) = ViewerLaunchContext(
        query, 0, source, 0,
        recentsSection = RecentPostSection.WATCHED.takeIf { source == ViewerStreamSource.RECENTS },
    )
}
