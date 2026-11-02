package com.theoriacodex.app.settings

import com.theoriacodex.app.statistics.DailyStatistic
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

internal enum class StatsTrendRange(val label: String) {
    WEEK("7 days"),
    MONTH("30 days"),
    YEAR("Year"),
}

internal data class StatsTrendBar(val label: String, val foregroundMs: Long, val watchedPosts: Long)

/** Fills inactive days with empty bars, since history omits them. Year buckets by calendar month. */
internal fun statsTrendBars(
    history: List<DailyStatistic>,
    today: LocalDate,
    range: StatsTrendRange,
): List<StatsTrendBar> {
    if (range == StatsTrendRange.YEAR) {
        val byMonth = history.groupBy { YearMonth.from(it.date) }
        return (11L downTo 0L).map { back ->
            val month = YearMonth.from(today).minusMonths(back)
            bar(month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), byMonth[month].orEmpty())
        }
    }
    val days = if (range == StatsTrendRange.WEEK) 7L else 30L
    val byDate = history.associateBy(DailyStatistic::date)
    return (days - 1 downTo 0L).map { back ->
        val date = today.minusDays(back)
        bar("${date.monthValue}/${date.dayOfMonth}", listOfNotNull(byDate[date]))
    }
}

private fun bar(label: String, days: List<DailyStatistic>) =
    StatsTrendBar(label, days.sumOf(DailyStatistic::totalForegroundMs), days.sumOf(DailyStatistic::watchedPostCount))

/** Stats show minutes as their finest unit, except for durations under a minute. */
internal fun formatStatsDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    return when {
        hours > 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0L -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
