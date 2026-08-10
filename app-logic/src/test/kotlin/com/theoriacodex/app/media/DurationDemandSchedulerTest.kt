package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationDemandSchedulerTest {
    @Test
    fun `priority order is deterministic and FIFO within a priority`() {
        val scheduler = DurationDemandScheduler()
        scheduler.submit(demand("background", "3", DurationDemandPriority.BACKGROUND_IDLE))
        scheduler.submit(demand("near-first", "2", DurationDemandPriority.NEAR_VIEWPORT))
        scheduler.submit(demand("visible", "4", DurationDemandPriority.VISIBLE))
        scheduler.submit(demand("near-second", "1", DurationDemandPriority.NEAR_VIEWPORT))
        scheduler.submit(demand("filter", "5", DurationDemandPriority.ACTIVE_FILTER))

        val gate = DurationExecutionGate(lifecycleStarted = true, scrollIdle = true)
        assertEquals("5", scheduler.takeNext(gate)?.key?.postId?.sourcePostId)
        assertEquals("4", scheduler.takeNext(gate)?.key?.postId?.sourcePostId)
        assertEquals("2", scheduler.takeNext(gate)?.key?.postId?.sourcePostId)
        assertEquals("1", scheduler.takeNext(gate)?.key?.postId?.sourcePostId)
        assertEquals("3", scheduler.takeNext(gate)?.key?.postId?.sourcePostId)
        assertNull(scheduler.takeNext(gate))
    }

    @Test
    fun `same fingerprint deduplicates consumers and promotes priority`() {
        val scheduler = DurationDemandScheduler()
        val key = key("shared")
        scheduler.submit(demand("search", key, DurationDemandPriority.BACKGROUND_IDLE))
        scheduler.submit(demand("creator", key, DurationDemandPriority.VISIBLE))
        scheduler.submit(demand("search", key, DurationDemandPriority.ACTIVE_FILTER))

        assertEquals(1, scheduler.queuedKeyCount())
        assertEquals(2, scheduler.queuedDemandCount())
        val work = requireNotNull(
            scheduler.takeNext(DurationExecutionGate(lifecycleStarted = true, scrollIdle = false)),
        )
        assertEquals(DurationDemandPriority.ACTIVE_FILTER, work.priority)
        assertEquals(setOf("creator", "search"), work.demands.mapTo(linkedSetOf(), DurationDemand::identity))
    }

    @Test
    fun `releasing one consumer preserves shared work and releasing last removes it`() {
        val scheduler = DurationDemandScheduler()
        val shared = key("shared")
        scheduler.submit(demand("search", shared, DurationDemandPriority.VISIBLE))
        scheduler.submit(demand("creator", shared, DurationDemandPriority.NEAR_VIEWPORT))

        scheduler.removeIdentity("search")
        assertTrue(scheduler.contains(shared))
        assertEquals(listOf("creator"), scheduler.demandsFor(shared).map(DurationDemand::identity))

        scheduler.removeIdentity("creator")
        assertFalse(scheduler.contains(shared))
    }

    @Test
    fun `bounded queue evicts only a lower priority key and rejects equal priority overflow`() {
        val scheduler = DurationDemandScheduler(maxQueuedKeys = 2)
        scheduler.submit(demand("background", "background", DurationDemandPriority.BACKGROUND_IDLE))
        scheduler.submit(demand("near", "near", DurationDemandPriority.NEAR_VIEWPORT))

        val accepted = scheduler.submit(demand("visible", "visible", DurationDemandPriority.VISIBLE))
        assertEquals(
            key("background"),
            (accepted as DurationDemandSubmission.Accepted).evictedKey,
        )
        assertFalse(scheduler.contains(key("background")))

        val secondAccepted = scheduler.submit(
            demand("visible-2", "visible-2", DurationDemandPriority.VISIBLE),
        )
        assertEquals(key("near"), (secondAccepted as DurationDemandSubmission.Accepted).evictedKey)
        val rejected = scheduler.submit(
            demand("visible-3", "visible-3", DurationDemandPriority.VISIBLE),
        )
        assertEquals(DurationDemandSubmission.Rejected, rejected)
        assertEquals(2, scheduler.queuedKeyCount())
    }

    @Test
    fun `lifecycle pauses all work and scrolling pauses background only`() {
        val scheduler = DurationDemandScheduler()
        scheduler.submit(demand("background", "background", DurationDemandPriority.BACKGROUND_IDLE))
        assertNull(
            scheduler.takeNext(DurationExecutionGate(lifecycleStarted = false, scrollIdle = true)),
        )
        assertNull(
            scheduler.takeNext(DurationExecutionGate(lifecycleStarted = true, scrollIdle = false)),
        )
        scheduler.submit(demand("visible", "visible", DurationDemandPriority.VISIBLE))
        assertEquals(
            key("visible"),
            scheduler.takeNext(
                DurationExecutionGate(lifecycleStarted = true, scrollIdle = false),
            )?.key,
        )
        assertTrue(scheduler.contains(key("background")))
    }

    private fun demand(
        identity: String,
        sourcePostId: String,
        priority: DurationDemandPriority,
    ): DurationDemand = demand(identity, key(sourcePostId), priority)

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

    private fun key(sourcePostId: String): MediaDurationKey {
        return MediaDurationKey(
            postId = PostId(SourceKey.HITOMI, sourcePostId),
            mediaFingerprint = "fingerprint-$sourcePostId",
        )
    }
}
