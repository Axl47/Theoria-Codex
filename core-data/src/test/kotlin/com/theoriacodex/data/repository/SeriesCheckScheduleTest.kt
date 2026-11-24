package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.SeriesEntry
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCheckScheduleTest {
    private val now = 1_000.days.inWholeMilliseconds
    private val day = 1.days.inWholeMilliseconds

    private fun releasedDaysAgo(vararg days: Long) = days.map { now - it * day }

    @Test
    fun `fewer than three releases give no pace, so the series is checked every run`() {
        assertNull(checkWait(releasedDaysAgo(400, 800), now))
    }

    @Test
    fun `the wait is a fraction of the median gap between recent releases`() {
        assertEquals((7.days / CHECKS_PER_RELEASE_GAP), checkWait(releasedDaysAgo(0, 7, 14, 21, 60), now))
    }

    @Test
    fun `a series quiet for longer than its usual gap waits by how long it has been quiet, up to the ceiling`() {
        assertEquals(32.days / CHECKS_PER_RELEASE_GAP, checkWait(releasedDaysAgo(32, 39, 46), now))
        assertEquals(SERIES_CHECK_CEILING, checkWait(releasedDaysAgo(700, 707, 714), now))
    }

    @Test
    fun `a series is skipped only while the next run still falls inside its wait, and is due at once when never checked or the clock went back`() {
        val weekly = listOf(0L, 7, 14).map { stored(now - it * day) }
        val runEvery = 6.hours
        val lastSkipped = now - (7.days / CHECKS_PER_RELEASE_GAP - runEvery).inWholeMilliseconds

        assertFalse(isCheckDue(lastSkipped, runEvery, now) { weekly })
        assertTrue(isCheckDue(lastSkipped - 1, runEvery, now) { weekly })
        assertTrue(isCheckDue(null, runEvery, now) { error("Never checked needs no entries") })
        assertTrue(isCheckDue(now + day, runEvery, now) { weekly })
        assertTrue(isCheckDue(now, runEvery, now) { weekly.take(2) })
    }

    @Test
    fun `a quiet series is checked by the last run before the ceiling, also after a check by hand between runs`() {
        val quiet = listOf(700L, 707, 714).map { stored(now - it * day) }
        val runEvery = 6.hours
        assertFalse(isCheckDue(now - 161.hours.inWholeMilliseconds, runEvery, now) { quiet })
        assertTrue(isCheckDue(now - 164.hours.inWholeMilliseconds, runEvery, now) { quiet })
    }

    @Test
    fun `a series whose recent chapters lack upload dates is checked every run`() {
        val undated = listOf(0L, 7, 14).map { StoredSeriesEntry(SeriesEntry("/c/$it", "$it"), 0, firstSeenAtEpochMs = now - (700 + it) * day) }
        val newestUndated = listOf(StoredSeriesEntry(SeriesEntry("/c/new", "new"), 0, firstSeenAtEpochMs = now)) +
            listOf(700L, 707, 714).map { stored(now - it * day) }
        assertTrue(isCheckDue(now, 6.hours, now) { undated })
        assertTrue(isCheckDue(now, 6.hours, now) { newestUndated })
    }

    private fun stored(releasedAt: Long) =
        StoredSeriesEntry(SeriesEntry("/c/$releasedAt", "c", uploadedAtEpochMs = releasedAt), 0, firstSeenAtEpochMs = now)
}
