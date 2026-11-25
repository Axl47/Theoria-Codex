package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DurationDecisionProjectionTest {
    @Test fun `a settled value stays after the bounded snapshot drops it (ADR 0068)`() {
        val first = MediaDurationKey(PostId(SourceKey.PIXIV, "first"), "media")
        val second = MediaDurationKey(PostId(SourceKey.PIXIV, "second"), "media")
        val known = MediaDurationState.Known(1_000, MediaDurationProvenance.PROVIDER)
        val observed = setOf(first, second)

        val settled = reconcileDurationDecisions(mapOf(first to known), mapOf(second to known), observed)

        assertEquals(mapOf(first to known, second to known), settled)
        assertEquals(settled, reconcileDurationDecisions(settled, emptyMap(), observed))
        val unsupported = MediaDurationState.Unsupported(MediaDurationUnsupportedReason.STATIC_MEDIA)
        assertEquals(
            mapOf(first to unsupported, second to known),
            reconcileDurationDecisions(mapOf(first to unsupported), mapOf(second to known), observed),
        )
    }

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
