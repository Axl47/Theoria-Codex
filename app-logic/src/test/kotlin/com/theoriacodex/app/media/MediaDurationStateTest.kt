package com.theoriacodex.app.media

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDurationStateTest {
    @Test
    fun `planner uses known and persisted decisions before acquisition work`() {
        assertEquals(
            DurationAcquisitionPlan.AlreadyKnown(4_200L),
            planDurationAcquisition(
                facts(
                    knownDurationMs = 4_200L,
                    persistedState = MediaDurationState.Unsupported(
                        MediaDurationUnsupportedReason.PROVIDER_UNSUPPORTED,
                    ),
                    hasAuthoritativeFullVideo = true,
                    hasProviderDurationResolver = true,
                ),
            ),
        )
        val persisted = MediaDurationState.Known(
            durationMs = 8_400L,
            provenance = MediaDurationProvenance.CONTAINER_PROBE,
        )
        assertEquals(
            DurationAcquisitionPlan.UsePersisted(persisted),
            planDurationAcquisition(
                facts(
                    persistedState = persisted,
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
    fun `pending process state never blocks authoritative acquisition`() {
        assertEquals(
            DurationAcquisitionPlan.ProbeAuthoritativeMedia,
            planDurationAcquisition(
                facts(
                    persistedState = MediaDurationState.Pending,
                    hasAuthoritativeFullVideo = true,
                ),
            ),
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
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
        assertTrue(first.all { character -> character in '0'..'9' || character in 'a'..'f' })
        assertTrue("7231" !in first)
    }

    private fun facts(
        knownDurationMs: Long? = null,
        persistedState: MediaDurationState? = null,
        hasAuthoritativeFullVideo: Boolean = false,
        hasProviderDurationResolver: Boolean = false,
    ): DurationAcquisitionFacts {
        return DurationAcquisitionFacts(
            knownDurationMs = knownDurationMs,
            persistedState = persistedState,
            hasAuthoritativeFullVideo = hasAuthoritativeFullVideo,
            hasProviderDurationResolver = hasProviderDurationResolver,
        )
    }
}
