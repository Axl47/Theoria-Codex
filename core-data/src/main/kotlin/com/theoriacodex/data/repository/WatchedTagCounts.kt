package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey

internal const val WATCHED_TAGS_PER_SOURCE = 4_000

internal fun LifetimeStatistics.recordWatchedTags(
    source: SourceKey,
    tags: Set<String>,
    limit: Int = WATCHED_TAGS_PER_SOURCE,
): LifetimeStatistics {
    val names = tags.mapNotNullTo(linkedSetOf()) { tag -> tag.trim().takeIf(String::isNotBlank) }
    if (names.isEmpty()) return this
    val table = HashMap<String, Long>()
    watchedByTag.forEach { (key, count) -> if (key.source == source) table[key.tag] = count }
    var floor = watchedTagFloors[source] ?: 0L
    for (name in names) {
        val count = table[name]
        if (count == null && table.size >= limit) floor = table.evictLowest()
        table[name] = (count ?: floor).saturatingAdd(1L)
    }
    return copy(
        watchedByTag = watchedByTag.filterKeys { it.source != source } + table.mapKeys { (tag, _) -> StatisticsTagKey(source, tag) },
        watchedTagFloors = if (floor > 0L) watchedTagFloors + (source to floor) else watchedTagFloors,
    )
}

internal fun LifetimeStatistics.capWatchedTags(limit: Int = WATCHED_TAGS_PER_SOURCE): LifetimeStatistics {
    val floors = watchedTagFloors.filterValues { it > 0L }.toMutableMap()
    watchedByTag.entries.groupBy({ it.key.source }, { it.value }).forEach { (source, counts) ->
        val floor = floors[source] ?: 0L
        val above = counts.filter { it > floor }
        if (above.size > limit) floors[source] = above.sortedDescending()[limit]
    }
    val kept = watchedByTag.filter { (key, count) -> count > (floors[key.source] ?: 0L) }
    return if (kept.size == watchedByTag.size && floors == watchedTagFloors) this else copy(watchedByTag = kept, watchedTagFloors = floors)
}

private fun MutableMap<String, Long>.evictLowest(): Long {
    val lowest = values.min()
    values.removeAll { it == lowest }
    return lowest
}
