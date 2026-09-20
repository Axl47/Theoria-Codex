package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.FileBackedReadingPositionRepository
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
@Config(application = Application::class, sdk = [35])
class ViewerRestorationWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()

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
            val firstOwner = ViewerViewModel(saved, scopeOverride = this@runTest)
            firstOwner.replaceSession(launch)
            firstOwner.onAction(ViewerAction.SelectPage(1))
            val coldOwner = ViewerViewModel(
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
            val resumed = workflow.prepareRecentSession(listOf(gallery), launch.context)
            assertEquals(1, resumed.initialMediaIndex)
            assertEquals(5, graph.data.recentsRepository.observeWatchedPosts().first().single().maxViewedMediaNumber)
            val fromDisk = FileBackedReadingPositionRepository(graph.data.storageDirectory)
            assertEquals(2, fromDisk.get(gallery.id, RecentPostSection.WATCHED)?.mediaNumber)

            val restarted = workflow.prepareRecentSession(listOf(gallery), launch.context, startOver = true)
            assertEquals(0, restarted.initialMediaIndex)
            assertEquals(5, graph.data.recentsRepository.observeWatchedPosts().first().single().maxViewedMediaNumber)
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
            val workflow = ViewerRouteWorkflow(
                graph.data.copy(readingPositions = FileBackedReadingPositionRepository(graph.data.storageDirectory)),
                this, graph.sources, { null }, { null }, { null },
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
