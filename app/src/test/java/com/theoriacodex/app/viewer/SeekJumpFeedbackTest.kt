package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekJumpFeedbackTest {
    @Test
    fun `formatting includes forward sign and seconds`() {
        assertEquals("+ 10s", formatSeekJumpFeedback(10_000L))
    }

    @Test
    fun `formatting includes backward sign and seconds`() {
        assertEquals("- 10s", formatSeekJumpFeedback(-10_000L))
    }

    @Test
    fun `same direction stacks before feedback expires`() {
        val first = nextSeekJumpFeedback(
            previous = null,
            deltaMs = 10_000L,
            nowElapsedMs = 1_000L,
            nextSerial = 1,
        )
        val second = nextSeekJumpFeedback(
            previous = first,
            deltaMs = 10_000L,
            nowElapsedMs = 1_100L,
            nextSerial = 2,
        )
        val third = nextSeekJumpFeedback(
            previous = second,
            deltaMs = 10_000L,
            nowElapsedMs = 1_200L,
            nextSerial = 3,
        )

        assertEquals(30_000L, third?.totalDeltaMs)
        assertEquals("+ 30s", formatSeekJumpFeedback(third?.totalDeltaMs ?: 0L))
    }

    @Test
    fun `opposite direction resets before feedback expires`() {
        val first = nextSeekJumpFeedback(
            previous = null,
            deltaMs = 10_000L,
            nowElapsedMs = 1_000L,
            nextSerial = 1,
        )
        val second = nextSeekJumpFeedback(
            previous = first,
            deltaMs = -10_000L,
            nowElapsedMs = 1_100L,
            nextSerial = 2,
        )

        assertEquals(SeekJumpDirection.Backward, second?.direction)
        assertEquals(-10_000L, second?.totalDeltaMs)
        assertEquals("- 10s", formatSeekJumpFeedback(second?.totalDeltaMs ?: 0L))
    }

    @Test
    fun `same direction after expiry resets instead of stacking`() {
        val first = nextSeekJumpFeedback(
            previous = null,
            deltaMs = 10_000L,
            nowElapsedMs = 1_000L,
            nextSerial = 1,
        )
        val second = nextSeekJumpFeedback(
            previous = first,
            deltaMs = 10_000L,
            nowElapsedMs = first!!.expiresAtElapsedMs,
            nextSerial = 2,
        )

        assertEquals(10_000L, second?.totalDeltaMs)
        assertEquals("+ 10s", formatSeekJumpFeedback(second?.totalDeltaMs ?: 0L))
    }

    @Test
    fun `zero delta produces no feedback`() {
        assertNull(
            nextSeekJumpFeedback(
                previous = null,
                deltaMs = 0L,
                nowElapsedMs = 1_000L,
                nextSerial = 1,
            ),
        )
    }
}
