package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDurationStateTest {
    @Test
    fun `planner uses a known duration before acquisition work`() {
        assertEquals(
            DurationAcquisitionPlan.AlreadyKnown(4_200L),
            planDurationAcquisition(
                facts(
                    knownDurationMs = 4_200L,
                    hasAuthoritativeFullVideo = true,
                    hasProviderDurationResolver = true,
                ),
            ),
        )
    }

    @Test
    fun `planner probes existing full video before asking an optional provider`() {
        assertEquals(
            DurationAcquisitionPlan.ProbeAuthoritativeMedia,
            planDurationAcquisition(
                facts(
                    hasAuthoritativeFullVideo = true,
                    hasProviderDurationResolver = true,
                ),
            ),
        )
    }

    @Test
    fun `planner asks only an advertised provider and otherwise settles unsupported`() {
        assertEquals(
            DurationAcquisitionPlan.AskProvider,
            planDurationAcquisition(facts(hasProviderDurationResolver = true)),
        )
        assertEquals(
            DurationAcquisitionPlan.Unsupported(
                MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA,
            ),
            planDurationAcquisition(facts()),
        )
    }

    @Test
    fun `fingerprint is stable opaque and changes with authoritative media identity`() {
        val base = MediaDurationFingerprintInput(
            postId = PostId(SourceKey.HITOMI, "7231"),
            normalizedAuthoritativeMediaIdentity = "https://stream.example/videos/7231.mp4",
            mime = "video/mp4",
            mediaCount = 1,
        )
        val first = mediaDurationFingerprint(base)
        val second = mediaDurationFingerprint(base)
        val changed = mediaDurationFingerprint(
            base.copy(normalizedAuthoritativeMediaIdentity = "https://stream.example/videos/7231-v2.mp4"),
        )

        assertEquals(first, second)
        assertEquals(
            "d024ade7f5590c6d32d0c70f063b7556ce88dbb1f08c88b546726b444e6c357c",
            first,
        )
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
        assertTrue(first.all { character -> character in '0'..'9' || character in 'a'..'f' })
        assertTrue("7231" !in first)
    }

    private fun facts(
        knownDurationMs: Long? = null,
        hasAuthoritativeFullVideo: Boolean = false,
        hasProviderDurationResolver: Boolean = false,
    ): DurationAcquisitionFacts {
        return DurationAcquisitionFacts(
            knownDurationMs = knownDurationMs,
            hasAuthoritativeFullVideo = hasAuthoritativeFullVideo,
            hasProviderDurationResolver = hasProviderDurationResolver,
        )
    }
}
