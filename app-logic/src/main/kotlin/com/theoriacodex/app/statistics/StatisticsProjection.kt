package com.theoriacodex.app.statistics

import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.data.repository.UsageDurationDelta
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import kotlin.math.roundToInt

data class SourceStatistic(
    val source: SourceKey,
    val count: Long,
    val percentage: Int,
)

data class TagStatistic(
    val key: StatisticsTagKey,
    val count: Long,
    val percentage: Int,
)

data class CodexUsageStatistic(
    val codexId: String,
    val name: String,
    val entryCount: Long,
)

data class StatisticsSummary(
    val appOpenCount: Long = 0L,
    val totalForegroundMs: Long = 0L,
    val browsingMs: Long = 0L,
    val watchingMs: Long = 0L,
    val codexMs: Long = 0L,
    val watchedPostCount: Long = 0L,
    val watchedSources: List<SourceStatistic> = emptyList(),
    val savedPostCount: Long = 0L,
    val savedSources: List<SourceStatistic> = emptyList(),
    val forYouSaveCount: Long = 0L,
    val postUrlCopyCount: Long = 0L,
    val searchCount: Long = 0L,
    val searchSources: List<SourceStatistic> = emptyList(),
    val forYouSearchCount: Long = 0L,
    val topWatchedTags: List<TagStatistic> = emptyList(),
    val topSavedTags: List<TagStatistic> = emptyList(),
    val mostUsedCodex: CodexUsageStatistic? = null,
    val leastUsedCodex: CodexUsageStatistic? = null,
    val topCodexSources: List<SourceStatistic> = emptyList(),
)

object StatisticsProjection {
    fun build(
        lifetime: LifetimeStatistics,
        liveUsage: UsageDurationDelta = UsageDurationDelta(),
        codices: List<Codex> = emptyList(),
        postsByCodex: Map<String, List<Post>> = emptyMap(),
    ): StatisticsSummary {
        val savedPosts = codices.asSequence()
            .flatMap { codex -> postsByCodex[codex.codexId].orEmpty().asSequence() }
            .distinctBy(Post::id)
            .toList()
        val savedCount = savedPosts.size.toLong()
        val savedSourceCounts = savedPosts.groupingBy { post -> post.id.source }.eachCount()
            .mapValues { (_, count) -> count.toLong() }
        val savedTagCounts = savedPosts
            .flatMap { post -> statisticsTagsForPost(post).map { tag -> StatisticsTagKey(post.id.source, tag) } }
            .groupingBy { key -> key }
            .eachCount()
            .mapValues { (_, count) -> count.toLong() }
        val codexUsage = codices.map { codex ->
            CodexUsageStatistic(
                codexId = codex.codexId,
                name = codex.name,
                entryCount = lifetime.codexEntryCounts[codex.codexId] ?: 0L,
            )
        }
        return StatisticsSummary(
            appOpenCount = lifetime.appOpenCount,
            totalForegroundMs = lifetime.totalForegroundMs.saturatingAdd(liveUsage.totalMs),
            browsingMs = lifetime.browsingMs.saturatingAdd(liveUsage.browsingMs),
            watchingMs = lifetime.watchingMs.saturatingAdd(liveUsage.watchingMs),
            codexMs = lifetime.codexMs.saturatingAdd(liveUsage.codexMs),
            watchedPostCount = lifetime.watchedPostCount,
            watchedSources = sourceRows(lifetime.watchedBySource, lifetime.watchedPostCount),
            savedPostCount = savedCount,
            savedSources = sourceRows(savedSourceCounts, savedCount),
            forYouSaveCount = lifetime.forYouSaveCount,
            postUrlCopyCount = lifetime.postUrlCopyCount,
            searchCount = lifetime.searchCount,
            searchSources = sourceRows(lifetime.searchesBySource, lifetime.searchCount),
            forYouSearchCount = lifetime.forYouSearchCount,
            topWatchedTags = tagRows(lifetime.watchedByTag, lifetime.watchedPostCount),
            topSavedTags = tagRows(savedTagCounts, savedCount),
            mostUsedCodex = codexUsage.sortedWith(
                compareByDescending<CodexUsageStatistic> { row -> row.entryCount }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { row -> row.name }
                    .thenBy { row -> row.codexId }
            ).firstOrNull(),
            leastUsedCodex = codexUsage.sortedWith(
                compareBy<CodexUsageStatistic> { row -> row.entryCount }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { row -> row.name }
                    .thenBy { row -> row.codexId }
            ).firstOrNull(),
            topCodexSources = sourceRows(savedSourceCounts, savedCount),
        )
    }

    private fun sourceRows(counts: Map<SourceKey, Long>, denominator: Long): List<SourceStatistic> {
        return counts.entries.asSequence()
            .filter { (_, count) -> count > 0L }
            .sortedWith(compareByDescending<Map.Entry<SourceKey, Long>> { entry -> entry.value }
                .thenBy { entry -> entry.key.name })
            .map { (source, count) -> SourceStatistic(source, count, percentage(count, denominator)) }
            .toList()
    }

    private fun tagRows(counts: Map<StatisticsTagKey, Long>, denominator: Long): List<TagStatistic> {
        return counts.entries.asSequence()
            .filter { (key, count) -> key.tag.isNotBlank() && count > 0L }
            .sortedWith(compareByDescending<Map.Entry<StatisticsTagKey, Long>> { entry -> entry.value }
                .thenBy { entry -> entry.key.source.name }
                .thenBy { entry -> entry.key.tag })
            .take(TOP_TAG_LIMIT)
            .map { (key, count) -> TagStatistic(key, count, percentage(count, denominator)) }
            .toList()
    }
}

fun statisticsTagsForPost(post: Post): Set<String> {
    val rawTags = post.taxonomy
        .asSequence()
        .filter { term -> term.facet == SearchFacet.TAG }
        .map { term -> term.value }
        .toList()
        .ifEmpty { post.canonicalTags }
    return rawTags.mapNotNullTo(linkedSetOf()) { tag ->
        sourceTagKey(post.id.source, tag).takeIf(String::isNotBlank)
    }
}

fun formatStatisticsDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "%dh %02dm %02ds".format(hours, minutes, seconds)
}

private fun percentage(count: Long, denominator: Long): Int {
    if (count <= 0L || denominator <= 0L) return 0
    return ((count.toDouble() / denominator.toDouble()) * 100.0).roundToInt().coerceAtLeast(0)
}

private fun Long.saturatingAdd(delta: Long): Long {
    val normalizedDelta = delta.coerceAtLeast(0L)
    return if (this > Long.MAX_VALUE - normalizedDelta) Long.MAX_VALUE else this + normalizedDelta
}

private const val TOP_TAG_LIMIT = 5
