package com.theoriacodex.app.ui.routes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.theoriacodex.app.fixtures.JourneyAppContainer
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerProviderWorkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `long Codex launch normalizes posts outside the route thread and preserves selection`() = runTest {
        val post = testPost(SourceKey.PIXIV, "gallery")
        val graph = JourneyAppContainer(
            ApplicationProvider.getApplicationContext(), temporary.newFolder(), emptyMap(), this,
        )
        try {
            graph.awaitReady()
            val routeThread = Thread.currentThread()
            var visited = 0
            val posts = object : AbstractList<Post>() {
                override val size = 2_000
                override fun get(index: Int): Post {
                    assertNotSame(routeThread, Thread.currentThread())
                    visited += 1
                    return post.copy(id = post.id.copy(sourcePostId = "gallery-$index"))
                }
            }
            val workflow = ViewerRouteWorkflow(
                graph.data, this, graph.sources, { null }, { null }, { null },
                offlineMedia = graph.features.offlineMedia,
            )

            val prepared = workflow.preparePostsForLaunch(
                posts, ViewerLaunchContext("codex:long", 1_500, ViewerStreamSource.CODEX, 0),
            )

            assertEquals(2_000, visited)
            assertEquals(2_000, prepared.size)
            assertEquals("gallery-1500", prepared[1_500].id.sourcePostId)
            assertEquals("gallery-1999", prepared.last().id.sourcePostId)
            assertTrue(graph.registry.resolveRequests.isEmpty())
            assertSame(routeThread, Thread.currentThread())
        } finally {
            graph.shutdown()
        }
    }

    @Test
    fun `cancelled launch stops normalizing a long Codex before visiting the rest`() = runTest {
        val post = testPost(SourceKey.PIXIV, "gallery")
        val graph = JourneyAppContainer(
            ApplicationProvider.getApplicationContext(), temporary.newFolder(), emptyMap(), this,
        )
        try {
            graph.awaitReady()
            lateinit var preparation: Job
            var visited = 0
            val posts = object : AbstractList<Post>() {
                override val size = 2_000
                override fun get(index: Int): Post {
                    visited += 1
                    if (index == 10) preparation.cancel()
                    return post
                }
            }
            val workflow = ViewerRouteWorkflow(
                graph.data, this, graph.sources, { null }, { null }, { null },
                offlineMedia = graph.features.offlineMedia,
                providerDispatcher = StandardTestDispatcher(testScheduler),
            )
            preparation = launch {
                workflow.preparePostsForLaunch(
                    posts, ViewerLaunchContext("codex:long", 0, ViewerStreamSource.CODEX, 0),
                )
                error("A cancelled launch must not return a prepared session")
            }
            preparation.join()

            assertTrue(preparation.isCancelled)
            assertTrue("Cancellation must stop before traversing the entire Codex", visited < posts.size)
            assertTrue(graph.registry.resolveRequests.isEmpty())
        } finally {
            graph.shutdown()
        }
    }

    @Test
    fun `resolution and recovery parse outside route dispatcher and cancel with the caller`() = runTest {
        val post = testPost(SourceKey.GELBOORU, "animated")
        val graph = JourneyAppContainer(
            ApplicationProvider.getApplicationContext(), temporary.newFolder(),
            mapOf(SourceKey.GELBOORU to listOf(post)), this,
        )
        try {
            graph.awaitReady()
            val dispatchers = mutableListOf<ContinuationInterceptor?>()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var suspendResolution = false
            val delegate = requireNotNull(graph.sources.registry.adapterFor(SourceKey.GELBOORU))
            val adapter = object : SourceAdapter by delegate {
                override suspend fun resolvePost(id: PostId) = post.also {
                    dispatchers += currentCoroutineContext()[ContinuationInterceptor]
                    if (suspendResolution) {
                        started.complete(Unit)
                        try { awaitCancellation() } finally { cancelled.complete(Unit) }
                    }
                }
            }
            val registry = object : SourceAdapterRegistry by graph.sources.registry {
                override fun adapterFor(sourceKey: SourceKey) = adapter.takeIf { sourceKey == SourceKey.GELBOORU }
            }
            val workflow = ViewerRouteWorkflow(
                graph.data, this, graph.sources.copy(registry = registry), { null }, { null }, { null },
            )
            val callerDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            assertEquals(post, workflow.resolvePost(post.id, ViewerStreamSource.RELATED))
            assertEquals(post, workflow.recoverMedia(post, post.preview, ViewerStreamSource.RELATED))
            assertEquals(2, dispatchers.size)
            dispatchers.forEach { assertSame(Dispatchers.Default, it) }
            assertSame(callerDispatcher, currentCoroutineContext()[ContinuationInterceptor])

            suspendResolution = true
            val job = launch { workflow.resolvePost(post.id, ViewerStreamSource.RELATED) }
            started.await()
            job.cancel()
            cancelled.await()
            job.join()
        } finally {
            graph.shutdown()
        }
    }
}
