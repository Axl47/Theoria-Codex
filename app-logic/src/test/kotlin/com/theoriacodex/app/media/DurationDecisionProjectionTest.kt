package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DurationDecisionProjectionTest {
    @Test fun `a settlement inspects bounded incoming keys rather than traversing any size feed`() {
        for (size in listOf(1_000, 10_000, 50_000)) {
            val keys = (0 until size).map { MediaDurationKey(PostId(SourceKey.PIXIV, "$it"), "media") }.toSet()
            val observed = object : Set<MediaDurationKey> by keys {
                override fun iterator(): Iterator<MediaDurationKey> = error("Whole-feed traversal is forbidden")
            }
            val first = keys.first()
            val known = MediaDurationState.Known(1_000, MediaDurationProvenance.PROVIDER)
            val initial = reconcileDurationDecisions(emptyMap(), mapOf(first to known), observed)
            assertEquals(mapOf(first to known), initial)
            assertSame(initial, reconcileDurationDecisions(initial, emptyMap(), observed))
            assertSame(initial, reconcileDurationDecisions(initial, mapOf(first to known), observed))
            val other = MediaDurationKey(PostId(SourceKey.PIXIV, "other-route"), "media")
            assertSame(initial, reconcileDurationDecisions(initial, mapOf(other to known), observed))
        }
    }
}
