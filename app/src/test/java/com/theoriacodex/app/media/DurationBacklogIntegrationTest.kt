package com.theoriacodex.app.media

import com.theoriacodex.app.testing.animatedTestPost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DurationBacklogIntegrationTest {
    @Test fun `a paused collection larger than the default queue completely settles without another UI event`() = runTest {
        var acquisitions = 0
        val coordinator = MediaDurationCoordinator(
            acquirer = MediaDurationAcquirer { acquisitions++; known() }, parentScope = backgroundScope,
            traceRecorder = NoOpMediaDurationTraceRecorder,
        )
        val owner = MediaDurationRouteViewModel(coordinator, "large-collection", backgroundScope)
        val posts = List(513) { animatedTestPost(sourcePostId = "$it") }
        owner.synchronize("collection", posts, resolveInBackground = false)
        owner.onFilterChanged(true)
        runCurrent()
        assertEquals(0, acquisitions)
        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        runCurrent()
        assertEquals(513, acquisitions)
        assertEquals(513, owner.states.value.values.count { it is MediaDurationState.Known })
        assertFalse(durationFilterReadiness(posts, true, owner.states.value.mapKeys { it.key.postId }).isResolving)
        coordinator.close()
    }

    @Test fun `priority eviction and releasing one deferred consumer preserve the remaining work`() = runTest {
        val requested = mutableListOf<String>()
        val coordinator = MediaDurationCoordinator(
            acquirer = MediaDurationAcquirer { requested += it.id.sourcePostId; known() },
            parentScope = backgroundScope, maxQueuedKeys = 1, traceRecorder = NoOpMediaDurationTraceRecorder,
        )
        val background = animatedTestPost(sourcePostId = "background")
        val shared = animatedTestPost(sourcePostId = "shared")
        val urgent = animatedTestPost(sourcePostId = "urgent")
        coordinator.submit(background, demand("background-owner", background, DurationDemandPriority.BACKGROUND_IDLE))
        assertEquals(DurationDemandAdmission.DEFERRED,
            coordinator.submit(shared, demand("search", shared, DurationDemandPriority.BACKGROUND_IDLE)))
        coordinator.submit(urgent, demand("filter", urgent, DurationDemandPriority.ACTIVE_FILTER))
        coordinator.submit(shared, demand("creator", shared, DurationDemandPriority.VISIBLE))
        coordinator.releaseIdentities(setOf("search", "background-owner"))
        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)
        runCurrent()
        assertEquals(listOf("urgent", "shared"), requested)
        coordinator.close()
    }

    @Test fun `a filter retains its settled decisions when the shared process cache evicts them`() = runTest {
        val coordinator = MediaDurationCoordinator(
            acquirer = MediaDurationAcquirer { known() }, parentScope = backgroundScope,
            maxQueuedKeys = 1, maxRetainedStates = 2, traceRecorder = NoOpMediaDurationTraceRecorder,
        )
        val owner = MediaDurationRouteViewModel(coordinator, "evicted-decisions", backgroundScope)
        val posts = List(5) { animatedTestPost(sourcePostId = "$it") }
        owner.synchronize("collection", posts, resolveInBackground = false)
        owner.onFilterChanged(true)
        runCurrent()
        owner.onEnvironmentChanged(lifecycleStarted = true, scrollIdle = true)
        runCurrent()
        assertEquals(2, coordinator.states.value.size)
        assertEquals(5, owner.states.value.values.count { it is MediaDurationState.Known })
        coordinator.close()
    }

    private fun known() = MediaDurationState.Known(10_000L, MediaDurationProvenance.CONTAINER_PROBE)
    private fun demand(owner: String, post: com.theoriacodex.domain.model.Post, priority: DurationDemandPriority) =
        DurationDemand(owner, mediaDurationKey(post), priority, DurationDemandReason.FILTER)
}
