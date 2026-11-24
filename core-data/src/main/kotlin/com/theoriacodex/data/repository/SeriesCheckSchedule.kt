package com.theoriacodex.data.repository

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

val SERIES_CHECK_CEILING: Duration = 2.days

internal const val CHECKS_PER_RELEASE_GAP = 24

private const val RECENT_RELEASES = 10

private const val FEWEST_RELEASES = 3

internal fun checkWait(entries: List<StoredSeriesEntry>, now: Long): Duration? {
    val releases = entries.mapNotNull { it.entry.uploadedAtEpochMs }.sortedDescending().take(RECENT_RELEASES)
    if (releases.size < FEWEST_RELEASES) return null
    val gap = releases.zipWithNext(Long::minus).sorted().let { it[it.size / 2] }
    val firstListed = entries.minOf { it.firstSeenAtEpochMs }
    val lastFound = entries.maxOf { it.firstSeenAtEpochMs }.takeIf { it > firstListed } ?: Long.MIN_VALUE
    val quiet = now - maxOf(releases.first(), lastFound)
    return (maxOf(gap, quiet) / CHECKS_PER_RELEASE_GAP).milliseconds.coerceAtMost(SERIES_CHECK_CEILING)
}

internal inline fun isCheckDue(checkedAt: Long?, runEvery: Duration, now: Long, entries: () -> List<StoredSeriesEntry>): Boolean {
    val elapsed = now - (checkedAt ?: return true)
    if (elapsed < 0 || elapsed.milliseconds + runEvery > SERIES_CHECK_CEILING) return true
    return elapsed.milliseconds + runEvery > (checkWait(entries(), now) ?: return true)
}
