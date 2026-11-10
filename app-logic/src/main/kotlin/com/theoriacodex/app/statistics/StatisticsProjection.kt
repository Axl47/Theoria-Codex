package com.theoriacodex.app.statistics

import com.theoriacodex.data.repository.CodexStatistics
import com.theoriacodex.data.repository.CodexPostStatistics
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.StatisticsTagKey
import com.theoriacodex.data.repository.UsageDurationDelta
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import com.theoriacodex.data.repository.rankedStatisticsPosts
import com.theoriacodex.data.repository.DailyActivity
import com.theoriacodex.data.repository.retainedHistory
import com.theoriacodex.data.repository.withDailyUsage
import java.time.LocalDate
import java.util.Locale
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

data class CodexStatisticsSummary(
    val codexId: String,
    val name: String,
    val entries: Long,
    val browsingMs: Long,
    val watchingMs: Long,
    val postsSeen: Long,
    val topOpened: List<CodexPostStatistic>,
    val topVideos: List<CodexPostStatistic>,
    val topImages: List<CodexPostStatistic>,
    /** Every recorded post, which is what lets a shared copy leave one out and rerank the rest. */
    val posts: Map<String, CodexPostStatistics> = emptyMap(),
    val thumbnails: Map<String, String> = emptyMap(),
)

data class CodexPostStatistic(
    val key: String,
    val label: String,
    val value: Long,
    val percentage: Int,
    val thumbnail: String? = null,
)

/** Local calendar activity, oldest first in the summary; inactive days may be omitted. */
data class DailyStatistic(
    val date: LocalDate,
    val appOpenCount: Long = 0L,
    val totalForegroundMs: Long = 0L,
    val browsingMs: Long = 0L,
    val watchingMs: Long = 0L,
    val codexMs: Long = 0L,
    val watchedPostCount: Long = 0L,
    val searchCount: Long = 0L,
)

/** One recommendation profile's Codices, for the surfaces that span every profile. */
data class ProfileStatistics(
    val profileId: String,
    val name: String,
    val codices: List<CodexStatisticsSummary>,
)

data class StatisticsSummary(
    val history: List<DailyStatistic> = emptyList(),
    val codices: List<CodexStatisticsSummary> = emptyList(),
    val profiles: List<ProfileStatistics> = emptyList(),
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
    val translatedPhraseCount: Long = 0L,
    val translatedSourceCharacterCount: Long = 0L,
    val topWatchedTags: List<TagStatistic> = emptyList(),
    val topSavedTags: List<TagStatistic> = emptyList(),
    val mostUsedCodex: CodexUsageStatistic? = null,
    val leastUsedCodex: CodexUsageStatistic? = null,
    val topCodexSources: List<SourceStatistic> = emptyList(),
)

/** Process-only saved-library projection; timer updates never read Posts. */
data class SavedContentStatistics(
    val codices: List<Codex> = emptyList(),
    val postCount: Long = 0L,
    val sources: List<SourceStatistic> = emptyList(),
    val tags: List<TagStatistic> = emptyList(),
)

object StatisticsProjection {
    fun build(
        lifetime: LifetimeStatistics,
        liveUsage: UsageDurationDelta = UsageDurationDelta(),
        codices: List<Codex> = emptyList(),
        postsByCodex: Map<String, List<Post>> = emptyMap(),
        savedContent: SavedContentStatistics = prepareContent(codices, postsByCodex),
    ): StatisticsSummary {
        val codexUsage = savedContent.codices.map { codex ->
            CodexUsageStatistic(
                codexId = codex.codexId,
                name = codex.name,
                entryCount = lifetime.codexEntryCounts[codex.codexId] ?: 0L,
            )
        }
        return StatisticsSummary(
            history = dailyRows(lifetime.history.withDailyUsage(liveUsage.dailyUsage)),
            codices = codexStatistics(lifetime, savedContent.codices),
            appOpenCount = lifetime.appOpenCount,
            totalForegroundMs = lifetime.totalForegroundMs.saturatingAdd(liveUsage.totalMs),
            browsingMs = lifetime.browsingMs.saturatingAdd(liveUsage.browsingMs),
            watchingMs = lifetime.watchingMs.saturatingAdd(liveUsage.watchingMs),
            codexMs = lifetime.codexMs.saturatingAdd(liveUsage.codexMs),
            watchedPostCount = lifetime.watchedPostCount,
            watchedSources = sourceRows(lifetime.watchedBySource, lifetime.watchedPostCount),
            savedPostCount = savedContent.postCount,
            savedSources = savedContent.sources,
            forYouSaveCount = lifetime.forYouSaveCount,
            postUrlCopyCount = lifetime.postUrlCopyCount,
            searchCount = lifetime.searchCount,
            searchSources = sourceRows(lifetime.searchesBySource, lifetime.searchCount),
            forYouSearchCount = lifetime.forYouSearchCount,
            translatedPhraseCount = lifetime.translatedPhraseCount,
            translatedSourceCharacterCount = lifetime.translatedSourceCharacterCount,
            topWatchedTags = tagRows(lifetime.watchedByTag, lifetime.watchedPostCount),
            topSavedTags = savedContent.tags,
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
            topCodexSources = savedContent.sources,
        )
    }

    fun prepareContent(codices: List<Codex>, postsByCodex: Map<String, List<Post>>): SavedContentStatistics =
        prepareContent(codices, codices.flatMap { postsByCodex[it.codexId].orEmpty() })

    fun prepareContent(codices: List<Codex>, posts: List<Post>): SavedContentStatistics {
        val savedPosts = posts.distinctBy(Post::id)
        val savedCount = savedPosts.size.toLong()
        val savedSourceCounts = savedPosts.groupingBy { post -> post.id.source }.eachCount()
            .mapValues { (_, count) -> count.toLong() }
        val savedTagCounts = savedPosts
            .flatMap { post -> statisticsTagsForPost(post).map { tag -> StatisticsTagKey(post.id.source, tag) } }
            .groupingBy { key -> key }
            .eachCount()
            .mapValues { (_, count) -> count.toLong() }
        return SavedContentStatistics(codices, savedCount, sourceRows(savedSourceCounts, savedCount),
            tagRows(savedTagCounts, savedCount))
    }

    private fun sourceRows(counts: Map<SourceKey, Long>, denominator: Long): List<SourceStatistic> {
        return counts.entries.asSequence()
            .filter { (_, count) -> count > 0L }
            .sortedWith(compareByDescending<Map.Entry<SourceKey, Long>> { entry -> entry.value }
                .thenBy { entry -> entry.key.id })
            .map { (source, count) -> SourceStatistic(source, count, percentage(count, denominator)) }
            .toList()
    }

    private fun tagRows(counts: Map<StatisticsTagKey, Long>, denominator: Long): List<TagStatistic> {
        return counts.entries.asSequence()
            .filter { (key, count) -> key.tag.isNotBlank() && count > 0L }
            .sortedWith(compareByDescending<Map.Entry<StatisticsTagKey, Long>> { entry -> entry.value }
                .thenBy { entry -> entry.key.source.id }
                .thenBy { entry -> entry.key.tag })
            .take(TOP_TAG_LIMIT)
            .map { (key, count) -> TagStatistic(key, count, percentage(count, denominator)) }
            .toList()
    }
}

/** Overlay live monotonic time without rebuilding content, source or tag statistics. */
fun StatisticsSummary.withLiveUsage(usage: UsageDurationDelta): StatisticsSummary = copy(
    history = dailyRows(history.associate { it.date to DailyActivity(it.appOpenCount, it.totalForegroundMs,
        it.browsingMs, it.watchingMs, it.codexMs, it.watchedPostCount, it.searchCount) }
        .withDailyUsage(usage.dailyUsage)),
    totalForegroundMs = totalForegroundMs.saturatingAdd(usage.totalMs),
    browsingMs = browsingMs.saturatingAdd(usage.browsingMs),
    watchingMs = watchingMs.saturatingAdd(usage.watchingMs),
    codexMs = codexMs.saturatingAdd(usage.codexMs),
)

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
    return "%dh %02dm %02ds".format(Locale.getDefault(), hours, minutes, seconds)
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

/** Profile-visible collections sorted by cumulative watching time, with deterministic ties. */
fun codexStatistics(lifetime: LifetimeStatistics, codices: List<Codex>): List<CodexStatisticsSummary> =
    codices.map { codex ->
        val stats = lifetime.codexStatistics[codex.codexId] ?: CodexStatistics()
        CodexStatisticsSummary(
            codex.codexId, codex.name, lifetime.codexEntryCounts[codex.codexId] ?: 0L,
            stats.browsingMs, stats.watchingMs, stats.posts.values.sumOf { it.opens },
            codexPostRows(stats.posts, lifetime.thumbnails) { it.opens },
            codexPostRows(stats.posts, lifetime.thumbnails) { it.videoMs },
            codexPostRows(stats.posts, lifetime.thumbnails) { it.imageMs },
            stats.posts,
            lifetime.thumbnails,
        )
    }.sortedWith(compareByDescending<CodexStatisticsSummary> { it.watchingMs }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.codexId })

private fun codexPostRows(
    posts: Map<String, CodexPostStatistics>,
    thumbnails: Map<String, String>,
    value: (CodexPostStatistics) -> Long,
): List<CodexPostStatistic> {
    val total = posts.values.sumOf(value)
    return rankedStatisticsPosts(posts, value).map { (key, post) ->
        CodexPostStatistic(key, post.label.ifBlank { key }, value(post), percentage(value(post), total), thumbnails[key])
    }
}

/** Only direct Codex launches carry collection attribution; Recents keeps its own launch identity. */
fun statisticsCodexId(context: ViewerLaunchContext?): String? =
    context?.takeIf { it.streamSource == ViewerStreamSource.CODEX }
        ?.queryHash?.takeIf { it.startsWith("codex:") }?.removePrefix("codex:")?.takeIf(String::isNotBlank)

fun statisticsPostKey(id: PostId): String = "${id.source.id}:${id.sourcePostId}"

private fun dailyRows(history: Map<LocalDate, DailyActivity>): List<DailyStatistic> =
    history.retainedHistory(LocalDate.now()).map { (date, day) -> DailyStatistic(
        date, day.appOpenCount, day.totalForegroundMs, day.browsingMs, day.watchingMs,
        day.codexMs, day.watchedPostCount, day.searchCount,
    ) }
