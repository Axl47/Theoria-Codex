package com.theoriacodex.app.comments

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val WEEK_MS = 7 * DAY_MS
private const val MONTH_MS = 30 * DAY_MS
private const val YEAR_MS = 365 * DAY_MS

fun commentAgeLabel(nowEpochMs: Long, createdAtEpochMs: Long?): String? {
    val elapsed = (nowEpochMs - (createdAtEpochMs ?: return null)).coerceAtLeast(0L)
    return when {
        elapsed < MINUTE_MS -> "now"
        elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}m"
        elapsed < DAY_MS -> "${elapsed / HOUR_MS}h"
        elapsed < WEEK_MS -> "${elapsed / DAY_MS}d"
        elapsed < MONTH_MS -> "${elapsed / WEEK_MS}w"
        elapsed < YEAR_MS -> "${elapsed / MONTH_MS}mo"
        else -> "${elapsed / YEAR_MS}y"
    }
}
