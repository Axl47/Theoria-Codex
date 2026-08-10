package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDurationCoordinatorTest {
    @Test
    fun `background demand waits for started idle environment`() = runTest {
        var acquisitions = 0
        val coordinator = coordinator(
            scope = this,
            acquirer = MediaDurationAcquirer {
                acquisitions += 1
                known(4_000L)
            },
        )
        val post = post("background")
        val key = key(post)

        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = false)
        assertTrue(coordinator.submit(post, demand("search", key, DurationDemandPriority.BACKGROUND_IDLE)))
        runCurrent()
        assertEquals(0, acquisitions)
        assertEquals(MediaDurationState.Pending, coordinator.states.value[key])

        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)
        runCurrent()
        assertEquals(1, acquisitions)
        assertEquals(known(4_000L), coordinator.states.value[key])
        coordinator.close()
    }

    @Test
    fun `same fingerprint shares one job and one remaining consumer keeps it alive`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<MediaDurationState>()
        var acquisitions = 0
        val coordinator = coordinator(
            scope = this,
            acquirer = MediaDurationAcquirer {
                acquisitions += 1
                started.complete(Unit)
                finish.await()
            },
        )
        val post = post("shared")
        val key = key(post)
        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)

        coordinator.submit(post, demand("search", key, DurationDemandPriority.VISIBLE))
        runCurrent()
        started.await()
        coordinator.submit(post, demand("creator", key, DurationDemandPriority.NEAR_VIEWPORT))
        coordinator.releaseIdentity("search")
        assertEquals(1, acquisitions)
        assertFalse(finish.isCancelled)

        finish.complete(known(9_000L))
        runCurrent()
        assertEquals(known(9_000L), coordinator.states.value[key])
        coordinator.close()
    }

    @Test
    fun `last consumer release cancels active work and removes pending state`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            scope = this,
            acquirer = MediaDurationAcquirer {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val post = post("cancel")
        val key = key(post)
        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)
        coordinator.submit(post, demand("search", key, DurationDemandPriority.VISIBLE))
        runCurrent()
        started.await()

        coordinator.releaseIdentity("search")
        runCurrent()
        cancelled.await()
        assertNull(coordinator.states.value[key])
        coordinator.close()
    }

    @Test
    fun `visible demand preempts background and background resumes afterward`() = runTest {
        val order = mutableListOf<String>()
        val backgroundStarted = CompletableDeferred<Unit>()
        var backgroundAttempts = 0
        val coordinator = coordinator(
            scope = this,
            acquirer = MediaDurationAcquirer { post ->
                order += post.id.sourcePostId
                if (post.id.sourcePostId == "background" && backgroundAttempts++ == 0) {
                    backgroundStarted.complete(Unit)
                    awaitCancellation()
                }
                known(if (post.id.sourcePostId == "visible") 2_000L else 8_000L)
            },
        )
        val background = post("background")
        val visible = post("visible")
        val backgroundKey = key(background)
        val visibleKey = key(visible)
        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)
        coordinator.submit(
            background,
            demand("search", backgroundKey, DurationDemandPriority.BACKGROUND_IDLE),
        )
        runCurrent()
        backgroundStarted.await()

        coordinator.submit(visible, demand("search", visibleKey, DurationDemandPriority.VISIBLE))
        runCurrent()

        assertEquals(listOf("background", "visible", "background"), order)
        assertEquals(known(2_000L), coordinator.states.value[visibleKey])
        assertEquals(known(8_000L), coordinator.states.value[backgroundKey])
        coordinator.close()
    }

    @Test
    fun `terminal state and player publication do not restart acquisition`() = runTest {
        var acquisitions = 0
        val traces = RecordingTraceRecorder()
        val coordinator = coordinator(
            scope = this,
            acquirer = MediaDurationAcquirer {
                acquisitions += 1
                known(5_000L)
            },
            traceRecorder = traces,
        )
        val post = post("terminal")
        val key = key(post)
        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)
        coordinator.submit(post, demand("search", key, DurationDemandPriority.VISIBLE))
        runCurrent()
        assertEquals(1, acquisitions)

        assertFalse(coordinator.submit(post, demand("creator", key, DurationDemandPriority.VISIBLE)))
        coordinator.publishKnown(key, 6_000L, MediaDurationProvenance.ACTIVE_PLAYER)
        runCurrent()

        assertEquals(1, acquisitions)
        assertEquals(
            MediaDurationState.Known(6_000L, MediaDurationProvenance.ACTIVE_PLAYER),
            coordinator.states.value[key],
        )
        assertEquals(1, traces.workloadStarts)
        assertEquals(1, traces.workloadFinishes)
        assertEquals(2, traces.publications)
        assertEquals(1, traces.settled)
        coordinator.close()
    }

    @Test
    fun `default coordinator executes at most one acquisition at a time`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        var completed = 0
        val coordinator = coordinator(
            scope = this,
            acquirer = MediaDurationAcquirer { post ->
                active += 1
                maximumActive = maxOf(maximumActive, active)
                if (post.id.sourcePostId == "first") releaseFirst.await()
                active -= 1
                completed += 1
                known(3_000L)
            },
        )
        coordinator.updateEnvironment(lifecycleStarted = true, scrollIdle = true)
        listOf("first", "second", "third").forEach { id ->
            val post = post(id)
            coordinator.submit(post, demand("search", key(post), DurationDemandPriority.VISIBLE))
        }
        runCurrent()
        assertEquals(1, maximumActive)
        assertEquals(0, completed)

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(1, maximumActive)
        assertEquals(3, completed)
        coordinator.close()
    }

    private fun coordinator(
        scope: CoroutineScope,
        acquirer: MediaDurationAcquirer,
        traceRecorder: MediaDurationTraceRecorder = NoOpMediaDurationTraceRecorder,
    ): MediaDurationCoordinator {
        return MediaDurationCoordinator(
            acquirer = acquirer,
            parentScope = scope,
            traceRecorder = traceRecorder,
        )
    }

    private fun known(durationMs: Long): MediaDurationState.Known {
        return MediaDurationState.Known(durationMs, MediaDurationProvenance.CONTAINER_PROBE)
    }

    private fun demand(
        identity: String,
        key: MediaDurationKey,
        priority: DurationDemandPriority,
    ): DurationDemand {
        return DurationDemand(
            identity = identity,
            key = key,
            priority = priority,
            reason = when (priority) {
                DurationDemandPriority.ACTIVE_FILTER -> DurationDemandReason.FILTER
                DurationDemandPriority.VISIBLE,
                DurationDemandPriority.NEAR_VIEWPORT,
                -> DurationDemandReason.VIEWPORT
                DurationDemandPriority.BACKGROUND_IDLE -> DurationDemandReason.APPEND
            },
        )
    }

    private fun key(post: Post): MediaDurationKey {
        return MediaDurationKey(post.id, "fingerprint-${post.id.sourcePostId}")
    }

    private fun post(id: String): Post {
        val media = ImageRef(
            url = "https://example.test/$id.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        return Post(
            id = PostId(SourceKey.HITOMI, id),
            preview = media,
            full = media,
            media = listOf(media),
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }

    private class RecordingTraceRecorder : MediaDurationTraceRecorder {
        var workloadStarts = 0
        var workloadFinishes = 0
        var publications = 0
        var settled = 0

        override fun demand() = Unit

        override fun workloadStarted(cookie: Int) {
            workloadStarts += 1
        }

        override fun workloadFinished(cookie: Int) {
            workloadFinishes += 1
        }

        override fun publication() {
            publications += 1
        }

        override fun settled() {
            settled += 1
        }
    }
}
