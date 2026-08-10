package com.theoriacodex.app.media

import com.theoriacodex.app.testing.animatedTestPost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDurationRouteViewModelTest {
    @Test
    fun `background setting off does no acquisition while player publication stays separate`() = runTest {
        var acquisitionCount = 0
        val coordinator = coordinator {
            acquisitionCount += 1
            MediaDurationState.Known(4_000L, MediaDurationProvenance.CONTAINER_PROBE)
        }
        val owner = MediaDurationRouteViewModel(coordinator, "search-test", backgroundScope)
        val post = animatedTestPost(sourcePostId = "post")

        owner.synchronize("query", listOf(post), resolveInBackground = false)
        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        runCurrent()

        assertEquals(0, acquisitionCount)
        assertFalse(coordinator.states.value.containsKey(mediaDurationKey(post)))

        owner.publishPlayerDuration(post, 3_500L)
        runCurrent()

        assertEquals(0, acquisitionCount)
        assertEquals(
            MediaDurationState.Known(3_500L, MediaDurationProvenance.ACTIVE_PLAYER),
            coordinator.states.value[mediaDurationKey(post)],
        )
        assertEquals(null, post.durationMs)
        coordinator.close()
    }

    @Test
    fun `active filter resolves loaded posts without rewriting them`() = runTest {
        val requested = mutableListOf<String>()
        val coordinator = coordinator { post ->
            requested += post.id.sourcePostId
            MediaDurationState.Known(6_000L, MediaDurationProvenance.CONTAINER_PROBE)
        }
        val owner = MediaDurationRouteViewModel(coordinator, "filter-test", backgroundScope)
        val post = animatedTestPost(sourcePostId = "filtered")

        owner.synchronize("query", listOf(post), resolveInBackground = false)
        owner.onFilterChanged(true)
        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = false)
        runCurrent()

        assertEquals(listOf("filtered"), requested)
        assertTrue(coordinator.states.value[mediaDurationKey(post)] is MediaDurationState.Known)
        assertEquals(null, post.durationMs)
        coordinator.close()
    }

    @Test
    fun `visibility reported before snapshot synchronization is not lost`() = runTest {
        val requested = mutableListOf<String>()
        val coordinator = coordinator { post ->
            requested += post.id.sourcePostId
            MediaDurationState.Known(5_000L, MediaDurationProvenance.CONTAINER_PROBE)
        }
        val owner = MediaDurationRouteViewModel(coordinator, "visibility-test", backgroundScope)
        val post = animatedTestPost(sourcePostId = "visible")

        owner.onPostVisibilityChanged(post, visible = true)
        owner.synchronize("query", listOf(post), resolveInBackground = true)
        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = false)
        runCurrent()

        assertEquals(listOf("visible"), requested)
        assertTrue(coordinator.states.value[mediaDurationKey(post)] is MediaDurationState.Known)
        coordinator.close()
    }

    @Test
    fun `same identity append submits only the new duration candidate`() = runTest {
        val traces = DemandCountingTraceRecorder()
        val coordinator = coordinator(
            traceRecorder = traces,
        ) { MediaDurationState.Known(5_000L, MediaDurationProvenance.CONTAINER_PROBE) }
        val owner = MediaDurationRouteViewModel(coordinator, "append-test", backgroundScope)
        val first = animatedTestPost(sourcePostId = "first")
        val second = animatedTestPost(sourcePostId = "second")

        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        owner.synchronize("query", listOf(first), resolveInBackground = true)
        runCurrent()
        owner.synchronize("query", listOf(first, second), resolveInBackground = true)
        runCurrent()

        assertEquals(2, traces.demands)
        coordinator.close()
    }

    @Test
    fun `route state ignores metadata published for another feed`() = runTest {
        val coordinator = coordinator {
            MediaDurationState.Known(5_000L, MediaDurationProvenance.CONTAINER_PROBE)
        }
        val searchOwner = MediaDurationRouteViewModel(coordinator, "search-state", backgroundScope)
        val creatorOwner = MediaDurationRouteViewModel(coordinator, "creator-state", backgroundScope)
        val searchPost = animatedTestPost(sourcePostId = "search")
        val creatorPost = animatedTestPost(sourcePostId = "creator")

        searchOwner.synchronize("query", listOf(searchPost), resolveInBackground = false)
        creatorOwner.synchronize("creator", listOf(creatorPost), resolveInBackground = false)
        runCurrent()
        creatorOwner.publishPlayerDuration(creatorPost, 5_000L)
        runCurrent()

        assertTrue(searchOwner.states.value.isEmpty())
        assertEquals(
            MediaDurationState.Known(5_000L, MediaDurationProvenance.ACTIVE_PLAYER),
            creatorOwner.states.value[mediaDurationKey(creatorPost)],
        )
        coordinator.close()
    }

    @Test
    fun `removing a post from the same identity cancels its stale demand`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val coordinator = coordinator {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val owner = MediaDurationRouteViewModel(coordinator, "removal-test", backgroundScope)
        val post = animatedTestPost(sourcePostId = "removed")

        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        owner.synchronize("query", listOf(post), resolveInBackground = true)
        runCurrent()
        started.await()
        owner.synchronize("query", emptyList(), resolveInBackground = true)
        runCurrent()
        cancelled.await()

        assertFalse(coordinator.states.value.containsKey(mediaDurationKey(post)))
        coordinator.close()
    }

    @Test
    fun `leaving the duration filter cancels visible work when background resolution is off`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val coordinator = coordinator {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val owner = MediaDurationRouteViewModel(coordinator, "filter-release-test", backgroundScope)
            val post = animatedTestPost(sourcePostId = "visible")

            owner.onFilterChanged(true)
            owner.onPostVisibilityChanged(post, visible = true)
            owner.synchronize("query", listOf(post), resolveInBackground = false)
            owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
            runCurrent()
            started.await()
            owner.onFilterChanged(false)
            runCurrent()
            cancelled.await()

            assertFalse(coordinator.states.value.containsKey(mediaDurationKey(post)))
            coordinator.close()
        }

    @Test
    fun `identity replacement cancels stale work and admits the fresh post`() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val oldPost = animatedTestPost(sourcePostId = "old")
        val freshPost = animatedTestPost(sourcePostId = "fresh")
        val coordinator = coordinator { post ->
            if (post.id == oldPost.id) {
                oldStarted.complete(Unit)
                oldRelease.await()
            }
            MediaDurationState.Known(7_000L, MediaDurationProvenance.CONTAINER_PROBE)
        }
        val owner = MediaDurationRouteViewModel(coordinator, "identity-test", backgroundScope)

        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        owner.synchronize("old-query", listOf(oldPost), resolveInBackground = true)
        runCurrent()
        oldStarted.await()
        owner.synchronize("fresh-query", listOf(freshPost), resolveInBackground = true)
        runCurrent()
        oldRelease.complete(Unit)
        runCurrent()

        assertFalse(coordinator.states.value.containsKey(mediaDurationKey(oldPost)))
        assertTrue(coordinator.states.value[mediaDurationKey(freshPost)] is MediaDurationState.Known)
        coordinator.close()
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        traceRecorder: MediaDurationTraceRecorder = NoOpMediaDurationTraceRecorder,
        acquirer: suspend (com.theoriacodex.domain.model.Post) -> MediaDurationState,
    ): MediaDurationCoordinator {
        return MediaDurationCoordinator(
            acquirer = MediaDurationAcquirer(acquirer),
            parentScope = this,
            traceRecorder = traceRecorder,
        )
    }

    private class DemandCountingTraceRecorder : MediaDurationTraceRecorder {
        var demands: Int = 0

        override fun demand() {
            demands += 1
        }

        override fun providerResolve() = Unit
        override fun probe() = Unit
        override fun workloadStarted(cookie: Int) = Unit
        override fun workloadFinished(cookie: Int) = Unit
        override fun publication() = Unit
        override fun settled() = Unit
    }
}
