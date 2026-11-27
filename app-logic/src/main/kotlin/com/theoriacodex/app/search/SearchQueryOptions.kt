package com.theoriacodex.app.search

import com.theoriacodex.domain.model.DateRange
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DateRangePreset(val label: String, val days: Int?) {
    NONE("Any time", null),
    TODAY("Today", 1),
    LAST_7_DAYS("7 days", 7),
    LAST_30_DAYS("30 days", 30),
    LAST_90_DAYS("90 days", 90),
    LAST_YEAR("1 year", 365),
}

enum class NhentaiLanguageFilter { ANY, ENGLISH, CHINESE, JAPANESE }

val DateRangePreset.range: DateRange?
    get() = days?.let(DateRange::LastDays)

fun presetFor(range: DateRange?): DateRangePreset? = when (range) {
    null -> DateRangePreset.NONE
    is DateRange.LastDays -> DateRangePreset.entries.firstOrNull { it.days == range.days }
    is DateRange.Between -> null
}

fun customDateRange(fromDayMs: Long, toDayMs: Long): DateRange.Between =
    DateRange.Between(fromDayMs, toDayMs + DateRange.DAY_MS - 1)

fun dateRangeLabel(range: DateRange): String = when (range) {
    is DateRange.LastDays -> presetFor(range)?.label ?: "${range.days} days"
    is DateRange.Between -> {
        val format = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).withZone(ZoneOffset.UTC)
        val date = { epochMs: Long? -> epochMs?.let { format.format(Instant.ofEpochMilli(it)) } ?: "…" }
        "${date(range.fromEpochMs)} – ${date(range.toEpochMs)}"
    }
}
