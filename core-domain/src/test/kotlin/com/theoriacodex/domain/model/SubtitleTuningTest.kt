package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTuningTest {
    @Test
    fun `size steps by ten percent within 50 to 200 and reset returns 100 (ADR 0224)`() {
        assertEquals(110, SubtitleAdjustment.Step(1).size(100))
        assertEquals(200, SubtitleAdjustment.Step(1).size(200))
        assertEquals(50, SubtitleAdjustment.Step(-3).size(60))
        assertEquals(100, SubtitleAdjustment.Reset.size(170))
    }

    @Test
    fun `delay steps by a tenth of a second in both directions up to thirty seconds and reset returns zero (ADR 0224)`() {
        assertEquals(-100L, SubtitleAdjustment.Step(-1).delay(0L))
        assertEquals(300L, SubtitleAdjustment.Step(4).delay(-100L))
        assertEquals(30_000L, SubtitleAdjustment.Step(1).delay(30_000L))
        assertEquals(-30_000L, SubtitleAdjustment.Step(-1).delay(-30_000L))
        assertEquals(0L, SubtitleAdjustment.Reset.delay(-2_500L))
    }
}
