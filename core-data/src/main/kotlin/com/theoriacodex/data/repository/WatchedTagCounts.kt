package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey

internal const val WATCHED_TAGS_PER_SOURCE = 4_000

internal fun LifetimeStatistics.recordWatchedTags(
    source: SourceKey,
    tags: Set<String>,
    limit: Int = WATCHED_TAGS_PER_SOURCE,
): LifetimeStatistics {
    val keys = tags.mapNotNullTo(linkedSetOf()) { tag -> tag.trim().takeIf(String::isNotBlank)?.let { StatisticsTagKey(source, it) } }
    if (keys.isEmpty()) return this
    val counts = watchedByTag.toMutableMap()
    val (seen, unseen) = keys.partition(counts::containsKey)
    seen.forEach { key -> counts[key] = counts.getValue(key).saturatingAdd(1L) }
    var size = counts.keys.count { it.source == source }
    var floor = watchedTagFloors[source] ?: 0L
    for (key in unseen.sortedBy(StatisticsTagKey::tag)) {
        if (size >= limit) {
            floor = counts.entries.asSequence().filter { it.key.source == source }.minOf { it.value }
            val before = counts.size
            counts.entries.removeAll { (evicted, count) -> evicted.source == source && count == floor }
            size -= before - counts.size
        }
        counts[key] = floor.saturatingAdd(1L)
        size++
    }
    return copy(watchedByTag = counts, watchedTagFloors = if (floor > 0L) watchedTagFloors + (source to floor) else watchedTagFloors)
}

internal fun LifetimeStatistics.capWatchedTags(limit: Int = WATCHED_TAGS_PER_SOURCE): LifetimeStatistics {
    val sizes = HashMap<SourceKey, Int>()
    var stale = watchedTagFloors.values.any { it <= 0L }
    watchedByTag.forEach { (key, count) ->
        if (count < (watchedTagFloors[key.source] ?: 0L)) stale = true else sizes.merge(key.source, 1, Int::plus)
    }
    if (!stale && sizes.values.none { it > limit }) return this
    val floors = watchedTagFloors.filterValues { it > 0L }.toMutableMap()
    val kept = watchedByTag.filterTo(HashMap()) { (key, count) -> count >= (floors[key.source] ?: 0L) }
    sizes.filterValues { it > limit }.keys.forEach { source ->
        val dropped = kept.entries.filter { it.key.source == source }
            .sortedWith(compareByDescending<Map.Entry<StatisticsTagKey, Long>> { it.value }.thenBy { it.key.tag })
            .drop(limit)
        floors[source] = dropped.first().value
        dropped.forEach { kept.remove(it.key) }
    }
    return copy(watchedByTag = kept, watchedTagFloors = floors)
}
