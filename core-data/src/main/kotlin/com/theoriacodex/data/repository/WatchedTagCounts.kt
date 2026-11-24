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
    val counts = watchedByTag.toMutableMap()
    var size = counts.keys.count { it.source == source }
    var floor = watchedTagFloors[source] ?: 0L
    for (name in names) {
        val key = StatisticsTagKey(source, name)
        val count = counts[key]
        if (count == null && size >= limit) {
            floor = counts.filterKeys { it.source == source }.values.min()
            val before = counts.size
            counts.entries.removeAll { (key, count) -> key.source == source && count == floor }
            size -= before - counts.size
        }
        if (count == null) size++
        counts[key] = (count ?: floor).saturatingAdd(1L)
    }
    return copy(watchedByTag = counts, watchedTagFloors = if (floor > 0L) watchedTagFloors + (source to floor) else watchedTagFloors)
}

internal fun LifetimeStatistics.capWatchedTags(limit: Int = WATCHED_TAGS_PER_SOURCE): LifetimeStatistics {
    val sizes = HashMap<SourceKey, Int>()
    var stale = watchedTagFloors.values.any { it <= 0L }
    watchedByTag.forEach { (key, count) ->
        if (count <= (watchedTagFloors[key.source] ?: 0L)) stale = true else sizes.merge(key.source, 1, Int::plus)
    }
    if (!stale && sizes.values.none { it > limit }) return this
    val floors = watchedTagFloors.filterValues { it > 0L }.toMutableMap()
    sizes.filterValues { it > limit }.keys.forEach { source ->
        val floor = floors[source] ?: 0L
        val above = watchedByTag.filter { (key, count) -> key.source == source && count > floor }.values
        floors[source] = above.sortedDescending()[limit]
    }
    return copy(watchedByTag = watchedByTag.filter { (key, count) -> count > (floors[key.source] ?: 0L) }, watchedTagFloors = floors)
}
