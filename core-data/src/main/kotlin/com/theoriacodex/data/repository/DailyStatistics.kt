package com.theoriacodex.data.repository

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val STATISTICS_HISTORY_DAYS = 400L

/** Independent daily counters; foreground includes uncategorized activity. */
data class DailyActivity(
    @field:SerializedName("appOpenCount") val appOpenCount: Long = 0L,
    @field:SerializedName("totalForegroundMs") val totalForegroundMs: Long = 0L,
    @field:SerializedName("browsingMs") val browsingMs: Long = 0L,
    @field:SerializedName("watchingMs") val watchingMs: Long = 0L,
    @field:SerializedName("codexMs") val codexMs: Long = 0L,
    @field:SerializedName("watchedPostCount") val watchedPostCount: Long = 0L,
    @field:SerializedName("searchCount") val searchCount: Long = 0L,
)

/** A monotonic duration allocated to a local calendar date. */
data class DailyUsageDuration(val date: LocalDate, val usage: UsageDurationDelta)

/** Split elapsed time using a wall-clock anchor, respecting local midnight and DST. */
fun splitDailyUsage(start: Instant, zone: ZoneId, usage: UsageDurationDelta): List<DailyUsageDuration> {
    if (usage.totalMs <= 0L) return emptyList()
    val end = start.plusMillis(usage.totalMs)
    // Only dates in the retention window need allocating, even after an unusually long interval.
    var cursor = maxOf(start, end.atZone(zone).toLocalDate().minusDays(STATISTICS_HISTORY_DAYS - 1)
        .atStartOfDay(zone).toInstant())
    val result = mutableListOf<DailyUsageDuration>()
    while (cursor < end) {
        val date = cursor.atZone(zone).toLocalDate()
        val boundary = minOf(end, date.plusDays(1).atStartOfDay(zone).toInstant())
        val offset = java.time.Duration.between(start, cursor).toMillis()
        val nextOffset = java.time.Duration.between(start, boundary).toMillis()
        fun part(value: Long): Long = minOf(value.coerceAtLeast(0L), nextOffset) -
            minOf(value.coerceAtLeast(0L), offset)
        result += DailyUsageDuration(date, UsageDurationDelta(
            totalMs = nextOffset - offset,
            browsingMs = part(usage.browsingMs),
            watchingMs = part(usage.watchingMs),
            codexMs = part(usage.codexMs),
        ))
        cursor = boundary
    }
    return result
}

fun Map<LocalDate, DailyActivity>.withDailyUsage(usage: List<DailyUsageDuration>): Map<LocalDate, DailyActivity> =
    usage.fold(this) { history, slice ->
        history.updateDay(slice.date) { day -> day.copy(
            totalForegroundMs = day.totalForegroundMs.saturatingAdd(slice.usage.totalMs),
            browsingMs = day.browsingMs.saturatingAdd(slice.usage.browsingMs),
            watchingMs = day.watchingMs.saturatingAdd(slice.usage.watchingMs),
            codexMs = day.codexMs.saturatingAdd(slice.usage.codexMs),
        ) }
    }

internal fun Map<LocalDate, DailyActivity>.updateDay(
    date: LocalDate,
    update: (DailyActivity) -> DailyActivity,
): Map<LocalDate, DailyActivity> = this + (date to update(this[date] ?: DailyActivity()))

fun Map<LocalDate, DailyActivity>.retainedHistory(today: LocalDate): Map<LocalDate, DailyActivity> =
    filterKeys { it >= today.minusDays(STATISTICS_HISTORY_DAYS - 1) }
        .toSortedMap().entries.toList().takeLast(STATISTICS_HISTORY_DAYS.toInt()).associate { it.toPair() }
