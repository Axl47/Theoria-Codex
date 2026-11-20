package com.theoriacodex.app.settings

import com.theoriacodex.app.statistics.DailyStatistic
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTrendTest {
    private val today = LocalDate.of(2026, 3, 2)

    @Test
    fun `week fills inactive days and ends today`() {
        val bars = statsTrendBars(
            listOf(DailyStatistic(today.minusDays(2), totalForegroundMs = 5L, watchedPostCount = 2L)),
            today,
            StatsTrendRange.WEEK,
        )
        assertEquals(7, bars.size)
        assertEquals(listOf(0L, 0L, 0L, 0L, 5L, 0L, 0L), bars.map(StatsTrendBar::foregroundMs))
        assertEquals("3/2", bars.last().label)
    }

    @Test
    fun `year sums days by month and ignores older history`() {
        val bars = statsTrendBars(
            listOf(
                DailyStatistic(LocalDate.of(2026, 3, 1), totalForegroundMs = 1L),
                DailyStatistic(LocalDate.of(2026, 3, 2), totalForegroundMs = 2L),
                DailyStatistic(LocalDate.of(2026, 2, 28), totalForegroundMs = 4L),
                DailyStatistic(LocalDate.of(2025, 3, 31), totalForegroundMs = 100L),
            ),
            today,
            StatsTrendRange.YEAR,
        )
        assertEquals(12, bars.size)
        assertEquals(3L, bars[11].foregroundMs)
        assertEquals(4L, bars[10].foregroundMs)
        assertEquals(7L, bars.sumOf(StatsTrendBar::foregroundMs))
    }

    @Test
    fun `durations drop seconds once minutes exist`() {
        assertEquals("45s", formatStatsDuration(45_000L))
        assertEquals("3m", formatStatsDuration(185_000L))
        assertEquals("64h 02m", formatStatsDuration(230_520_000L))
    }
}
