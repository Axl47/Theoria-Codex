package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationDemandBacklogTest {
    @Test
    fun `the backlog serves the strongest lane first and a stronger demand moves a key up (ADR 0068)`() {
        val backlog = DurationDemandBacklog()
        backlog.put(listOf(demand("search", "near", DurationDemandPriority.NEAR_VIEWPORT)))
        backlog.put(listOf(demand("search", "idle", DurationDemandPriority.BACKGROUND_IDLE)))
        backlog.put(listOf(demand("search", "visible", DurationDemandPriority.VISIBLE)))
        backlog.put(listOf(demand("filter", "idle", DurationDemandPriority.ACTIVE_FILTER)))

        assertEquals(listOf("idle", "visible", "near"), backlog.drain())
    }

    @Test
    fun `releasing a key's strongest demand returns it to the lane of the demand left (ADR 0068)`() {
        val backlog = DurationDemandBacklog()
        backlog.put(listOf(
            demand("search", "shared", DurationDemandPriority.BACKGROUND_IDLE),
            demand("filter", "shared", DurationDemandPriority.ACTIVE_FILTER),
            demand("search", "visible", DurationDemandPriority.VISIBLE),
        ))

        assertEquals(setOf(key("shared")), backlog.removeIdentities(setOf("filter")))
        assertEquals(listOf("visible", "shared"), backlog.drain())
    }

    private fun DurationDemandBacklog.drain(): List<String> =
        generateSequence { takeNext()?.first()?.key?.postId?.sourcePostId }.toList()

    private fun demand(identity: String, id: String, priority: DurationDemandPriority) =
        DurationDemand(identity = identity, key = key(id), priority = priority)

    private fun key(id: String) = MediaDurationKey(PostId(SourceKey.HITOMI, id), "fingerprint-$id")
}
