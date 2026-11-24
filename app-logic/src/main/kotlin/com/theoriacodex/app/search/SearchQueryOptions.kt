package com.theoriacodex.app.search

import com.theoriacodex.domain.model.DateRange
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class DateRangePreset(val label: String, val days: Int?) {
    NONE("Any time", null),
    TODAY("Today", 1),
    LAST_7_DAYS("7 days", 7),
    LAST_30_DAYS("30 days", 30),
    LAST_90_DAYS("90 days", 90),
    LAST_YEAR("1 year", 365),
}

enum class NhentaiLanguageFilter { ANY, ENGLISH, CHINESE, JAPANESE }

private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val PRESET_TOLERANCE_MS = 2L * 60L * 60L * 1000L

fun DateRangePreset.rangeEndingAt(nowEpochMs: Long): DateRange? =
    days?.let { DateRange(nowEpochMs - it * DAY_MS, nowEpochMs) }

fun presetFor(range: DateRange?, nowEpochMs: Long): DateRangePreset? {
    if (range == null) return DateRangePreset.NONE
    val from = range.fromEpochMs ?: return null
    val to = range.toEpochMs ?: return null
    if (abs(nowEpochMs - to) > PRESET_TOLERANCE_MS) return null
    return DateRangePreset.entries.firstOrNull { preset ->
        preset.days != null && abs((to - from) - preset.days * DAY_MS) <= PRESET_TOLERANCE_MS
    }
}

fun customDateRange(fromDayMs: Long, toDayMs: Long): DateRange = DateRange(fromDayMs, toDayMs + DAY_MS - 1)

fun dateRangeLabel(range: DateRange, nowEpochMs: Long): String {
    presetFor(range, nowEpochMs)?.let { return it.label }
    val format = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()).withZone(ZoneOffset.UTC)
    val date = { epochMs: Long? -> epochMs?.let { format.format(Instant.ofEpochMilli(it)) } ?: "…" }
    return "${date(range.fromEpochMs)} – ${date(range.toEpochMs)}"
}
