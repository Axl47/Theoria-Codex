package com.theoriacodex.app.recents

import com.theoriacodex.data.repository.RecentActivityEntry
import com.theoriacodex.data.repository.RecentPostEntry
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.Query
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the All tab: a day heading, a run of consecutive posts, or a search. */
internal sealed interface RecentsTimelineItem {
    data class Day(val label: String) : RecentsTimelineItem
    data class Posts(val entries: List<RecentPostEntry>) : RecentsTimelineItem
    data class Search(val entry: RecentSearchEntry) : RecentsTimelineItem
}

internal fun recentsDayLabel(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        else -> DAY_FORMAT.format(day)
    }
}

/** Groups newest-first activity by day, keeping consecutive posts together as one shelf. */
internal fun recentsTimeline(
    activity: List<RecentActivityEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentsTimelineItem> = buildList {
    var day: String? = null
    var posts = mutableListOf<RecentPostEntry>()
    fun flushPosts() {
        if (posts.isNotEmpty()) add(RecentsTimelineItem.Posts(posts))
        posts = mutableListOf()
    }
    activity.forEach { entry ->
        val label = recentsDayLabel(entry.occurredAtEpochMs, now, zone)
        if (label != day) {
            flushPosts()
            add(RecentsTimelineItem.Day(label))
            day = label
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

/** One cell of a post grid: a full-width day heading, or a post at its index in the visible list. */
internal sealed interface RecentsGridRow {
    data class Day(val label: String) : RecentsGridRow
    data class Post(val index: Int, val entry: RecentPostEntry) : RecentsGridRow
}

internal fun recentsGridRows(
    posts: List<RecentPostEntry>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentsGridRow> = posts.withIndex()
    .groupBy { (_, entry) -> recentsDayLabel(entry.viewedAtEpochMs, now, zone) }
    .flatMap { (day, entries) ->
        listOf(RecentsGridRow.Day(day)) + entries.map { (index, entry) -> RecentsGridRow.Post(index, entry) }
    }

/** A search's one-line name: its required tags, "or" alternatives, then exclusions. */
internal fun recentSearchRowTitle(query: Query): String {
    val required = query.effectiveIncludeTermGroups.map { group -> group.terms.joinToString(" or ") { it.value } }
    return (required + query.excludeTags.map { "-$it" }).joinToString(", ").ifBlank { "No tags" }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
