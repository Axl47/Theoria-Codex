package com.theoriacodex.app.viewer

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class EpisodeClipRangeTest {
    @Test fun `a clip starts at the playhead with the next 30 seconds, sliding back before the episode ends (ADR 0223)`() {
        assertEquals(EpisodeClipRange(600_000L, 630_000L), initialEpisodeClip(600_000L, 1_440_000L))
        assertEquals(EpisodeClipRange(1_410_000L, 1_440_000L), initialEpisodeClip(1_430_000L, 1_440_000L))
        assertEquals(EpisodeClipRange(0L, 20_000L), initialEpisodeClip(5_000L, 20_000L))
        assertNull(initialEpisodeClip(0L, 500L))
    }

    @Test fun `handles keep a clip between one second and one minute inside the episode (ADR 0223)`() {
        val clip = EpisodeClipRange(100_000L, 130_000L)
        assertEquals(EpisodeClipRange(70_000L, 130_000L), clip.withStart(10_000L))
        assertEquals(EpisodeClipRange(129_000L, 130_000L), clip.withStart(200_000L))
        assertEquals(EpisodeClipRange(100_000L, 160_000L), clip.withEnd(400_000L, 1_440_000L))
        assertEquals(EpisodeClipRange(100_000L, 150_000L), clip.withEnd(400_000L, 150_000L))
        assertEquals(EpisodeClipRange(100_000L, 101_000L), clip.withEnd(0L, 1_440_000L))
    }

    @Test fun `the strip shows two minutes centred on the clip, kept inside the episode (ADR 0223)`() {
        assertEquals(55_000L..175_000L, episodeClipWindow(EpisodeClipRange(100_000L, 130_000L), 1_440_000L))
        assertEquals(0L..120_000L, episodeClipWindow(EpisodeClipRange(5_000L, 35_000L), 1_440_000L))
        assertEquals(1_320_000L..1_440_000L, episodeClipWindow(EpisodeClipRange(1_420_000L, 1_440_000L), 1_440_000L))
        assertEquals(0L..40_000L, episodeClipWindow(EpisodeClipRange(5_000L, 35_000L), 40_000L))
    }
}
