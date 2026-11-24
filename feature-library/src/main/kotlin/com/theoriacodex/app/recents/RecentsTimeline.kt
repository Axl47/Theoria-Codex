package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal sealed interface RecentsTimelineItem {
    data class Day(val date: LocalDate, val label: String) : RecentsTimelineItem
    data class Searches(val date: LocalDate, val entries: List<RecentSearchEntry>) : RecentsTimelineItem
    data class Posts(val date: LocalDate, val entries: List<RecentPostEntry>) : RecentsTimelineItem
    data object RelatedShelf : RecentsTimelineItem
}

internal fun recentsDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

internal fun recentsDayLabel(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(day)
    }
}

internal fun recentsTimeline(
    activity: List<RecentActivityEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentsTimelineItem> = activity
    .groupBy { recentsDay(it.occurredAtEpochMs, zone) }
    .flatMap { (date, entries) ->
        val searches = entries.filterIsInstance<RecentActivityEntry.Search>().map { it.entry }
        val posts = entries.filterIsInstance<RecentActivityEntry.Watched>().map { it.entry }
        listOfNotNull(
            RecentsTimelineItem.Day(date, recentsDayLabel(entries.first().occurredAtEpochMs, now, zone)),
            searches.takeIf { it.isNotEmpty() }?.let { RecentsTimelineItem.Searches(date, it) },
            posts.takeIf { it.isNotEmpty() }?.let { RecentsTimelineItem.Posts(date, it) },
        )
    }

internal sealed interface RecentsGridRow {
    data class Day(val date: LocalDate, val label: String) : RecentsGridRow
    data class Post(val index: Int, val entry: RecentPostEntry) : RecentsGridRow
    data object RelatedShelf : RecentsGridRow
}

internal fun recentsGridRows(
    posts: List<RecentPostEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentsGridRow> = posts.withIndex()
    .groupBy { (_, entry) -> recentsDay(entry.viewedAtEpochMs, zone) }
    .flatMap { (day, entries) ->
        listOf(RecentsGridRow.Day(day, recentsDayLabel(entries.first().value.viewedAtEpochMs, now, zone))) + entries.map { (index, entry) -> RecentsGridRow.Post(index, entry) }
    }
