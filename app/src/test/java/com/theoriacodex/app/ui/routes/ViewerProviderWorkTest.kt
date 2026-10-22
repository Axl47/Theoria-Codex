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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
