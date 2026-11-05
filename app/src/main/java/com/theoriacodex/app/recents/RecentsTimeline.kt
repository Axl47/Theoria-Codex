package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.Query
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the All tab: a day heading, a run of consecutive posts, a search, or the related shelf. */
internal sealed interface RecentsTimelineItem {
    data class Day(val date: LocalDate, val label: String) : RecentsTimelineItem
    data class Posts(val entries: List<RecentPostEntry>) : RecentsTimelineItem
    data class Search(val entry: RecentSearchEntry) : RecentsTimelineItem
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
        // Built per call so a locale change while the app runs takes effect.
        else -> DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(day)
    }
}

/** Groups newest-first activity by day, keeping consecutive posts together as one shelf. */
internal fun recentsTimeline(
    activity: List<RecentActivityEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentsTimelineItem> = buildList {
    var day: LocalDate? = null
    var posts = mutableListOf<RecentPostEntry>()
    fun flushPosts() {
        if (posts.isNotEmpty()) add(RecentsTimelineItem.Posts(posts))
        posts = mutableListOf()
    }
    activity.forEach { entry ->
        val label = recentsDayLabel(entry.occurredAtEpochMs, now, zone)
        val date = recentsDay(entry.occurredAtEpochMs, zone)
        if (date != day) {
            flushPosts()
            add(RecentsTimelineItem.Day(date, label))
            day = date
        }
        when (entry) {
            is RecentActivityEntry.Watched -> posts += entry.entry
            is RecentActivityEntry.Search -> {
                flushPosts()
                add(RecentsTimelineItem.Search(entry.entry))
            }
        }
    }
    flushPosts()
}

/** One cell of a post grid: a full-width day heading, a post at its index in the visible list, or the related shelf. */
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

/** A search's one-line name: its required tags, "or" alternatives, then exclusions. */
internal fun recentSearchRowTitle(query: Query): String {
    val required = query.effectiveIncludeTermGroups.map { group -> group.terms.joinToString(" or ") { it.value } }
    return (required + query.excludeTags.map { "-$it" }).joinToString(", ").ifBlank { "No tags" }
}

