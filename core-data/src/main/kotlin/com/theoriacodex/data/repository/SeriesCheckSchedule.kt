package com.theoriacodex.data.repository

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

val SERIES_CHECK_CEILING: Duration = 7.days

internal const val CHECKS_PER_RELEASE_GAP = 24

internal const val RECENT_RELEASES = 10

private const val FEWEST_RELEASES = 3

internal fun checkWait(releases: List<Long>, now: Long): Duration? {
    val recent = releases.sortedDescending().take(RECENT_RELEASES)
    if (recent.size < FEWEST_RELEASES) return null
    val gap = recent.zipWithNext(Long::minus).sorted().let { it[it.size / 2] }
    val quiet = now - recent.first()
    return (maxOf(gap, quiet) / CHECKS_PER_RELEASE_GAP).milliseconds.coerceAtMost(SERIES_CHECK_CEILING)
}

internal inline fun isCheckDue(checkedAt: Long?, runEvery: Duration, now: Long, entries: () -> List<StoredSeriesEntry>): Boolean {
    val elapsed = now - (checkedAt ?: return true)
    if (elapsed < 0 || elapsed.milliseconds + runEvery > SERIES_CHECK_CEILING) return true
    val releases = entries().take(RECENT_RELEASES).map { it.entry.uploadedAtEpochMs ?: return true }
    return elapsed.milliseconds + runEvery > (checkWait(releases, now) ?: return true)
}
