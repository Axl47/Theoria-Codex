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
    private val firstListed = now - 800 * day

    private fun entry(uploadedDaysAgo: Long?, firstSeen: Long = firstListed) = StoredSeriesEntry(
        SeriesEntry("/c/$uploadedDaysAgo/$firstSeen", "c", uploadedAtEpochMs = uploadedDaysAgo?.let { now - it * day }),
        0,
        firstSeenAtEpochMs = firstSeen,
    )

    private fun dated(vararg daysAgo: Long) = daysAgo.map { entry(it) }

    @Test
    fun `fewer than three upload dates give no pace, so the series is checked every run`() {
        assertNull(checkWait(dated(400, 800), now))
        assertNull(checkWait(List(5) { entry(null) }, now))
        assertTrue(isCheckDue(now, 6.hours, now) { List(5) { entry(null) } })
    }

    @Test
    fun `the wait is a fraction of the median gap between the newest uploads, in whatever order the source lists them`() {
        assertEquals(7.days / CHECKS_PER_RELEASE_GAP, checkWait(dated(0, 7, 14, 21, 60), now))
        assertEquals(7.days / CHECKS_PER_RELEASE_GAP, checkWait(dated(60, 21, 14, 7, 0), now))
    }

    @Test
    fun `a series quiet for longer than its usual gap waits by how long it has been quiet, up to the ceiling`() {
        assertEquals(32.days / CHECKS_PER_RELEASE_GAP, checkWait(dated(32, 39, 46), now))
        assertEquals(SERIES_CHECK_CEILING, checkWait(dated(700, 707, 714), now))
    }

    @Test
    fun `a chapter a check found counts as a release, whatever date its source gives it`() {
        val quiet = dated(700, 707, 714)
        assertEquals(7.days / CHECKS_PER_RELEASE_GAP, checkWait(quiet + entry(721, firstSeen = now), now))
        assertEquals(7.days / CHECKS_PER_RELEASE_GAP, checkWait(quiet + entry(null, firstSeen = now), now))
    }

    @Test
    fun `a series is skipped only while the next run still falls inside its wait, and is due at once when never checked or the clock went back`() {
        val weekly = dated(0, 7, 14)
        val runEvery = 6.hours
        val lastSkipped = now - (7.days / CHECKS_PER_RELEASE_GAP - runEvery).inWholeMilliseconds

        assertFalse(isCheckDue(lastSkipped, runEvery, now) { weekly })
        assertTrue(isCheckDue(lastSkipped - 1, runEvery, now) { weekly })
        assertTrue(isCheckDue(null, runEvery, now) { error("Never checked needs no entries") })
        assertTrue(isCheckDue(now + day, runEvery, now) { weekly })
    }

    @Test
    fun `a quiet series is checked by the last run before the ceiling, also after a check by hand between runs`() {
        val quiet = dated(700, 707, 714)
        val runEvery = 6.hours
        val lastSkipped = now - (SERIES_CHECK_CEILING - runEvery).inWholeMilliseconds
        assertFalse(isCheckDue(lastSkipped, runEvery, now) { quiet })
        assertTrue(isCheckDue(lastSkipped - 1, runEvery, now) { quiet })
    }
}
