package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.JsonDataStore
import com.theoriacodex.data.storage.StateCell
import com.theoriacodex.data.storage.openDecodedJsonDataStore
import com.theoriacodex.data.storage.update
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DATASTORE_STATISTICS_FILE_NAME = "statistics_store_v1.json"
internal const val STATISTICS_DATASTORE_SCHEMA_VERSION = 2

@Serializable
internal data class StatisticsTagCountRecord(
    @SerialName("source") val source: String = "",
    @SerialName("tag") val tag: String = "",
    @SerialName("count") val count: Long = 0L,
)

@Serializable
internal data class StatisticsTypedTagCountRecord(
    @SerialName("source") val source: String = "",
    @SerialName("tag") val tag: String = "",
    @SerialName("facet") val facet: String = "",
    @SerialName("sourceNamespace") val sourceNamespace: String? = null,
    @SerialName("count") val count: Long = 0L,
)

@Serializable
internal data class StatisticsStoreRecord(
    @SerialName("history") val history: Map<String, DailyActivity> = emptyMap(),
    @SerialName("codexStatistics") val codexStatistics: Map<String, CodexStatistics> = emptyMap(),
    @SerialName("appOpenCount") val appOpenCount: Long = 0L,
    @SerialName("totalForegroundMs") val totalForegroundMs: Long = 0L,
    @SerialName("browsingMs") val browsingMs: Long = 0L,
    @SerialName("watchingMs") val watchingMs: Long = 0L,
    @SerialName("codexMs") val codexMs: Long = 0L,
    @SerialName("readingMs") val readingMs: Long = 0L,
    @SerialName("animeMs") val animeMs: Long = 0L,
    @SerialName("watchedPostCount") val watchedPostCount: Long = 0L,
    @SerialName("watchedBySource") val watchedBySource: Map<String, Long> = emptyMap(),
    @SerialName("watchedByTag") val watchedByTag: List<StatisticsTagCountRecord> = emptyList(),
    @SerialName("searchCount") val searchCount: Long = 0L,
    @SerialName("searchesBySource") val searchesBySource: Map<String, Long> = emptyMap(),
    @SerialName("searchesByTag") val searchesByTag: List<StatisticsTagCountRecord> = emptyList(),
    @SerialName("searchesByTagSeeded") val searchesByTagSeeded: Boolean = false,
    @SerialName("searchesByTypedTag") val searchesByTypedTag: List<StatisticsTypedTagCountRecord> = emptyList(),
    @SerialName("searchesByTypedTagSeeded") val searchesByTypedTagSeeded: Boolean = false,
    @SerialName("forYouSearchCount") val forYouSearchCount: Long = 0L,
    @SerialName("forYouSaveCount") val forYouSaveCount: Long = 0L,
    @SerialName("postUrlCopyCount") val postUrlCopyCount: Long = 0L,
    @SerialName("codexEntryCounts") val codexEntryCounts: Map<String, Long> = emptyMap(),
) {
    fun toDomain(): LifetimeStatistics {
        return StatisticsPolicies.normalize(
            LifetimeStatistics(
                history = history.mapKeys { (day, _) -> parseHistoryDay(day) },
                codexStatistics = codexStatistics,
                appOpenCount = appOpenCount,
                totalForegroundMs = totalForegroundMs,
                browsingMs = browsingMs,
                watchingMs = watchingMs,
                codexMs = codexMs,
                readingMs = readingMs,
                animeMs = animeMs,
                watchedPostCount = watchedPostCount,
                watchedBySource = watchedBySource.decodeSourceCounts(),
                watchedByTag = watchedByTag.decodeTagCounts(),
                searchCount = searchCount,
                searchesBySource = searchesBySource.decodeSourceCounts(),
                searchesByTag = decodeSearchedTagCounts(searchesByTag, searchesByTypedTag),
                searchesByTagSeeded = searchesByTagSeeded,
                searchesByTypedTagSeeded = searchesByTypedTagSeeded,
                forYouSearchCount = forYouSearchCount,
                forYouSaveCount = forYouSaveCount,
                postUrlCopyCount = postUrlCopyCount,
                codexEntryCounts = codexEntryCounts,
            )
        )
    }

    fun validate() {
        require(
            listOf(
                appOpenCount,
                totalForegroundMs,
                browsingMs,
                watchingMs,
                codexMs,
                readingMs,
                animeMs,
                watchedPostCount,
                searchCount,
                forYouSearchCount,
                forYouSaveCount,
                postUrlCopyCount,
            ).all { value -> value >= 0L }
        ) { "Statistics totals must be non-negative" }
        require(watchedBySource.values.all { value -> value >= 0L }) {
            "Watched source counts must be non-negative"
        }
        require(searchesBySource.values.all { value -> value >= 0L }) {
            "Search source counts must be non-negative"
        }
        require(watchedByTag.all { record -> record.count >= 0L }) {
            "Watched tag counts must be non-negative"
        }
        require(searchesByTag.all { record -> record.count >= 0L } && searchesByTypedTag.all { record -> record.count >= 0L }) {
            "Search tag counts must be non-negative"
        }
        require(codexEntryCounts.values.all { value -> value >= 0L }) {
            "Codex entry counts must be non-negative"
        }
        toDomain()
    }

    companion object {
        fun fromDomain(statistics: LifetimeStatistics): StatisticsStoreRecord {
            val normalized = StatisticsPolicies.normalize(statistics)
            return StatisticsStoreRecord(
                history = normalized.history.entries.associate { it.key.toString() to it.value },
                codexStatistics = normalized.codexStatistics,
                appOpenCount = normalized.appOpenCount,
                totalForegroundMs = normalized.totalForegroundMs,
                browsingMs = normalized.browsingMs,
                watchingMs = normalized.watchingMs,
                codexMs = normalized.codexMs,
                readingMs = normalized.readingMs,
                animeMs = normalized.animeMs,
                watchedPostCount = normalized.watchedPostCount,
                watchedBySource = normalized.watchedBySource.encodeSourceCounts(),
                watchedByTag = normalized.watchedByTag.encodeTagCounts(),
                searchCount = normalized.searchCount,
                searchesBySource = normalized.searchesBySource.encodeSourceCounts(),
                searchesByTag = normalized.searchesByTag.filterKeys { it.term.isPortableGeneralTag }
                    .mapKeys { (key, _) -> StatisticsTagKey(key.source, key.term.value) }.encodeTagCounts(),
                searchesByTagSeeded = normalized.searchesByTagSeeded,
                searchesByTypedTag = normalized.searchesByTag.filterKeys { !it.term.isPortableGeneralTag }.encodeTypedTagCounts(),
                searchesByTypedTagSeeded = normalized.searchesByTypedTagSeeded,
                forYouSearchCount = normalized.forYouSearchCount,
                forYouSaveCount = normalized.forYouSaveCount,
                postUrlCopyCount = normalized.postUrlCopyCount,
                codexEntryCounts = normalized.codexEntryCounts.toSortedMap(),
            )
        }
    }
}

@Serializable
internal data class StatisticsDataStoreFile(
    @SerialName("schemaVersion")
    val schemaVersion: Int = STATISTICS_DATASTORE_SCHEMA_VERSION,
    @SerialName("statistics")
    val statistics: StatisticsStoreRecord = StatisticsStoreRecord(),
) {
    fun toDomain(): LifetimeStatistics = statistics.toDomain()

    fun validate() {
        statistics.validate()
    }

    companion object {
        fun fromDomain(statistics: LifetimeStatistics): StatisticsDataStoreFile {
            return StatisticsDataStoreFile(statistics = StatisticsStoreRecord.fromDomain(statistics))
        }
    }
}

internal object StatisticsPolicies {
    fun mergeRestored(current: LifetimeStatistics, imported: LifetimeStatistics) = current.copy(
        history = current.history.mergeWith(imported.history) { a, b ->
            DailyActivity(
                appOpenCount = maxOf(a.appOpenCount, b.appOpenCount),
                totalForegroundMs = maxOf(a.totalForegroundMs, b.totalForegroundMs),
                browsingMs = maxOf(a.browsingMs, b.browsingMs),
                watchingMs = maxOf(a.watchingMs, b.watchingMs),
                codexMs = maxOf(a.codexMs, b.codexMs),
                readingMs = maxOf(a.readingMs, b.readingMs),
                animeMs = maxOf(a.animeMs, b.animeMs),
                watchedPostCount = maxOf(a.watchedPostCount, b.watchedPostCount),
                searchCount = maxOf(a.searchCount, b.searchCount),
            )
        },
        codexStatistics = current.codexStatistics.mergeWith(imported.codexStatistics) { a, b ->
            CodexStatistics(
                browsingMs = maxOf(a.browsingMs, b.browsingMs),
                watchingMs = maxOf(a.watchingMs, b.watchingMs),
                posts = a.posts.mergeWith(b.posts) { first, second ->
                    CodexPostStatistics(
                        label = first.label.ifBlank { second.label },
                        opens = maxOf(first.opens, second.opens),
                        videoMs = maxOf(first.videoMs, second.videoMs),
                        imageMs = maxOf(first.imageMs, second.imageMs),
                    )
                },
            )
        },
        appOpenCount = maxOf(current.appOpenCount, imported.appOpenCount),
        totalForegroundMs = maxOf(current.totalForegroundMs, imported.totalForegroundMs),
        browsingMs = maxOf(current.browsingMs, imported.browsingMs),
        watchingMs = maxOf(current.watchingMs, imported.watchingMs),
        codexMs = maxOf(current.codexMs, imported.codexMs),
        readingMs = maxOf(current.readingMs, imported.readingMs),
        animeMs = maxOf(current.animeMs, imported.animeMs),
        watchedPostCount = maxOf(current.watchedPostCount, imported.watchedPostCount),
        watchedBySource = current.watchedBySource.mergeWith(imported.watchedBySource, ::maxOf),
        watchedByTag = current.watchedByTag.mergeWith(imported.watchedByTag, ::maxOf),
        searchCount = maxOf(current.searchCount, imported.searchCount),
        searchesBySource = current.searchesBySource.mergeWith(imported.searchesBySource, ::maxOf),
        searchesByTag = current.searchesByTag.mergeWith(imported.searchesByTag, ::maxOf),
        searchesByTagSeeded = current.searchesByTagSeeded || imported.searchesByTagSeeded,
        searchesByTypedTagSeeded = current.searchesByTypedTagSeeded || imported.searchesByTypedTagSeeded,
        forYouSearchCount = maxOf(current.forYouSearchCount, imported.forYouSearchCount),
        forYouSaveCount = maxOf(current.forYouSaveCount, imported.forYouSaveCount),
        postUrlCopyCount = maxOf(current.postUrlCopyCount, imported.postUrlCopyCount),
        codexEntryCounts = current.codexEntryCounts.mergeWith(imported.codexEntryCounts, ::maxOf),
    )

    private fun <K, V> Map<K, V>.mergeWith(other: Map<K, V>, combine: (V, V) -> V): Map<K, V> =
        (keys + other.keys).associateWith { key ->
            val mine = this[key]
            val theirs = other[key]
            if (mine != null && theirs != null) combine(mine, theirs) else mine ?: requireNotNull(theirs)
        }

    fun normalize(statistics: LifetimeStatistics): LifetimeStatistics {
        return statistics.copy(
            appOpenCount = statistics.appOpenCount.coerceAtLeast(0L),
            totalForegroundMs = statistics.totalForegroundMs.coerceAtLeast(0L),
            browsingMs = statistics.browsingMs.coerceAtLeast(0L),
            watchingMs = statistics.watchingMs.coerceAtLeast(0L),
            codexMs = statistics.codexMs.coerceAtLeast(0L),
            readingMs = statistics.readingMs.coerceAtLeast(0L),
            animeMs = statistics.animeMs.coerceAtLeast(0L),
            watchedPostCount = statistics.watchedPostCount.coerceAtLeast(0L),
            watchedBySource = statistics.watchedBySource.normalizeCounts(),
            watchedByTag = statistics.watchedByTag.normalizeTagCounts(),
            searchCount = statistics.searchCount.coerceAtLeast(0L),
            searchesBySource = statistics.searchesBySource.normalizeCounts(),
            searchesByTag = statistics.searchesByTag.normalizeSearchedTagCounts(),
            forYouSearchCount = statistics.forYouSearchCount.coerceAtLeast(0L),
            forYouSaveCount = statistics.forYouSaveCount.coerceAtLeast(0L),
            postUrlCopyCount = statistics.postUrlCopyCount.coerceAtLeast(0L),
            codexEntryCounts = statistics.codexEntryCounts.entries.mapNotNull { (codexId, count) ->
                val normalizedId = codexId.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val normalizedCount = count.coerceAtLeast(0L).takeIf { it > 0L } ?: return@mapNotNull null
                normalizedId to normalizedCount
            }.toMap(),
        )
    }

    fun addCodexUsage(current: LifetimeStatistics, target: CodexUsageTarget, durationMs: Long): LifetimeStatistics {
        if (target.codexId.isBlank() || durationMs <= 0L) return current
        val previous = current.codexStatistics[target.codexId] ?: CodexStatistics()
        val next = if (target.postKey == null) previous.copy(browsingMs = previous.browsingMs.saturatingAdd(durationMs))
        else {
            val post = previous.posts[target.postKey] ?: CodexPostStatistics(label = target.postKey)
            previous.copy(
                watchingMs = previous.watchingMs.saturatingAdd(durationMs),
                posts = previous.posts + (target.postKey to post.copy(
                    videoMs = post.videoMs.saturatingAdd(if (target.video) durationMs else 0L),
                    imageMs = post.imageMs.saturatingAdd(if (target.video) 0L else durationMs),
                )),
            )
        }
        return current.copy(codexStatistics = current.codexStatistics + (target.codexId to next))
    }

    fun recordCodexPostOpen(current: LifetimeStatistics, codexId: String, postKey: String, label: String): LifetimeStatistics {
        if (codexId.isBlank() || postKey.isBlank()) return current
        val previous = current.codexStatistics[codexId] ?: CodexStatistics()
        val post = previous.posts[postKey] ?: CodexPostStatistics()
        return current.copy(codexStatistics = current.codexStatistics + (codexId to previous.copy(
            posts = previous.posts + (postKey to post.copy(label = label.take(160), opens = post.opens.saturatingAdd(1L))),
        )))
    }

    fun recordAppOpen(current: LifetimeStatistics, date: LocalDate = LocalDate.now()): LifetimeStatistics {
        return current.copy(
            appOpenCount = current.appOpenCount.saturatingAdd(1L),
            history = current.history.updateDay(date) { it.copy(appOpenCount = it.appOpenCount.saturatingAdd(1L)) },
        )
    }

    fun addUsage(current: LifetimeStatistics, delta: UsageDurationDelta, date: LocalDate = LocalDate.now()): LifetimeStatistics {
        return current.copy(
            history = current.history.withDailyUsage(delta.dailyUsage.ifEmpty { listOf(DailyUsageDuration(date, delta)) }),
            totalForegroundMs = current.totalForegroundMs.saturatingAdd(delta.totalMs),
            browsingMs = current.browsingMs.saturatingAdd(delta.browsingMs),
            watchingMs = current.watchingMs.saturatingAdd(delta.watchingMs),
            codexMs = current.codexMs.saturatingAdd(delta.codexMs),
            readingMs = current.readingMs.saturatingAdd(delta.readingMs),
            animeMs = current.animeMs.saturatingAdd(delta.animeMs),
        )
    }

    fun recordWatched(
        current: LifetimeStatistics,
        source: SourceKey,
        tags: Set<String>,
        date: LocalDate = LocalDate.now(),
    ): LifetimeStatistics {
        return current.copy(
            history = current.history.updateDay(date) { it.copy(watchedPostCount = it.watchedPostCount.saturatingAdd(1L)) },
            watchedPostCount = current.watchedPostCount.saturatingAdd(1L),
            watchedBySource = current.watchedBySource.increment(source),
            watchedByTag = current.watchedByTag.incrementTags(setOf(source), tags),
        )
    }

    fun recordSearch(
        current: LifetimeStatistics,
        sources: Set<SourceKey>,
        terms: Set<SearchTerm> = emptySet(),
        date: LocalDate = LocalDate.now(),
    ): LifetimeStatistics {
        var sourceCounts = current.searchesBySource
        sources.forEach { source -> sourceCounts = sourceCounts.increment(source) }
        return current.copy(
            history = current.history.updateDay(date) { it.copy(searchCount = it.searchCount.saturatingAdd(1L)) },
            searchCount = current.searchCount.saturatingAdd(1L),
            searchesBySource = sourceCounts,
            searchesByTag = current.searchesByTag.incrementTerms(sources, terms),
        )
    }

    fun seedSearchedTags(current: LifetimeStatistics, recentSearches: List<RecentSearchEntry>): LifetimeStatistics {
        if (current.searchesByTagSeeded && current.searchesByTypedTagSeeded) return current
        val unseeded = { term: SearchTerm ->
            if (term.isPortableGeneralTag) !current.searchesByTagSeeded else !current.searchesByTypedTagSeeded
        }
        val seeded = recentSearches
            .fold(emptyMap<SearchedTagKey, Long>()) { counts, entry ->
                counts.incrementTerms(entry.sources.toSet(), entry.query.searchedTerms().filterTo(mutableSetOf(), unseeded))
            }
        return current.copy(
            searchesByTag = current.searchesByTag.mergeWith(seeded, ::maxOf),
            searchesByTagSeeded = true,
            searchesByTypedTagSeeded = true,
        )
    }

    fun recordForYouSave(current: LifetimeStatistics): LifetimeStatistics {
        return current.copy(forYouSaveCount = current.forYouSaveCount.saturatingAdd(1L))
    }

    fun recordPostUrlCopy(current: LifetimeStatistics): LifetimeStatistics {
        return current.copy(postUrlCopyCount = current.postUrlCopyCount.saturatingAdd(1L))
    }

    fun recordCodexEntry(current: LifetimeStatistics, codexId: String): LifetimeStatistics {
        val normalized = codexId.trim()
        if (normalized.isBlank()) return current
        return current.copy(codexEntryCounts = current.codexEntryCounts.increment(normalized))
    }
}

class DataStoreStatisticsRepository private constructor(
    store: JsonDataStore<LifetimeStatistics>,
    clock: Clock,
) : StatisticsRepository by CellStatisticsRepository(store, clock), AsynchronousStore by store {
    constructor(
        baseDirectory: File,
        scope: CoroutineScope,
        recoveryRegistry: FileStoreRecoveryRegistry = FileStoreRecoveryRegistry(),
        clock: Clock = Clock.systemDefaultZone(),
    ) : this(
        openDecodedJsonDataStore(
            file = baseDirectory.resolve(DATASTORE_STATISTICS_FILE_NAME),
            logicalStore = "Statistics",
            schemaVersion = STATISTICS_DATASTORE_SCHEMA_VERSION,
            defaultValue = StatisticsDataStoreFile(),
            serializer = StatisticsDataStoreFile.serializer(),
            scope = scope,
            validate = StatisticsDataStoreFile::validate,
            recoveries = recoveryRegistry,
            decode = StatisticsDataStoreFile::toDomain,
            encode = StatisticsDataStoreFile::fromDomain,
        ),
        clock,
    )
}

internal class CellStatisticsRepository(
    private val cell: StateCell<LifetimeStatistics>,
    private val clock: Clock = Clock.systemDefaultZone(),
) : StatisticsRepository {
    override fun observeStatistics(): Flow<LifetimeStatistics> = cell.data.distinctUntilChanged()

    override suspend fun addCodexUsage(target: CodexUsageTarget, durationMs: Long) = mutate {
        StatisticsPolicies.addCodexUsage(it, target, durationMs)
    }

    override suspend fun recordCodexPostOpen(codexId: String, postKey: String, label: String) = mutate {
        StatisticsPolicies.recordCodexPostOpen(it, codexId, postKey, label)
    }

    override suspend fun recordAppOpen() = mutate { StatisticsPolicies.recordAppOpen(it, LocalDate.now(clock)) }

    override suspend fun addUsageDuration(delta: UsageDurationDelta) = mutate { current ->
        StatisticsPolicies.addUsage(current, delta, LocalDate.now(clock))
    }

    override suspend fun recordWatchedPost(source: SourceKey, tags: Set<String>, codexOpen: CodexPostOpen?) =
        mutate { current ->
            val watched = StatisticsPolicies.recordWatched(current, source, tags, LocalDate.now(clock))
            codexOpen?.let { StatisticsPolicies.recordCodexPostOpen(watched, it.codexId, it.postKey, it.label) } ?: watched
        }

    override suspend fun recordSearch(sources: Set<SourceKey>, terms: Set<SearchTerm>) = mutate { current ->
        StatisticsPolicies.recordSearch(current, sources, terms, LocalDate.now(clock))
    }

    override suspend fun seedSearchedTags(recentSearches: List<RecentSearchEntry>) = mutate { current ->
        StatisticsPolicies.seedSearchedTags(current, recentSearches)
    }


    override suspend fun recordForYouSave() = mutate(StatisticsPolicies::recordForYouSave)

    override suspend fun recordPostUrlCopy() = mutate(StatisticsPolicies::recordPostUrlCopy)

    override suspend fun recordCodexEntry(codexId: String) = mutate { current ->
        StatisticsPolicies.recordCodexEntry(current, codexId)
    }

    override suspend fun mergeRestored(imported: LifetimeStatistics) = mutate { current ->
        StatisticsPolicies.mergeRestored(current, imported)
    }

    private suspend fun mutate(transform: (LifetimeStatistics) -> LifetimeStatistics) {
        cell.update { current ->
            val next = StatisticsPolicies.normalize(transform(current))
            next.copy(history = next.history.retainedHistory(LocalDate.now(clock)))
        }
    }
}

internal fun Long.saturatingAdd(delta: Long): Long {
    val normalizedDelta = delta.coerceAtLeast(0L)
    return if (this > Long.MAX_VALUE - normalizedDelta) Long.MAX_VALUE else this + normalizedDelta
}

private fun parseHistoryDay(day: String): LocalDate = try {
    LocalDate.parse(day)
} catch (invalid: DateTimeParseException) {
    throw IllegalArgumentException("Statistics history key is not a date", invalid)
}

private fun Map<String, Long>.decodeSourceCounts(): Map<SourceKey, Long> {
    return entries.mapNotNull { (name, count) ->
        val source = SourceKey.parse(name) ?: return@mapNotNull null
        val normalizedCount = count.takeIf { it > 0L } ?: return@mapNotNull null
        source to normalizedCount
    }.toMap()
}

private fun Map<SourceKey, Long>.encodeSourceCounts(): Map<String, Long> {
    return entries.sortedBy { it.key.id }.associate { (source, count) -> source.id to count }
}

private fun Map<SourceKey, Long>.normalizeCounts(): Map<SourceKey, Long> {
    return mapValues { (_, count) -> count.coerceAtLeast(0L) }.filterValues { count -> count > 0L }
}

private fun <K> Map<K, Long>.increment(key: K): Map<K, Long> {
    return toMutableMap().apply { this[key] = (this[key] ?: 0L).saturatingAdd(1L) }
}

private fun Map<StatisticsTagKey, Long>.incrementTags(sources: Set<SourceKey>, tags: Set<String>): Map<StatisticsTagKey, Long> {
    val normalizedTags = tags.mapNotNullTo(linkedSetOf()) { tag -> tag.trim().takeIf(String::isNotBlank) }
    if (normalizedTags.isEmpty() || sources.isEmpty()) return this
    return toMutableMap().apply {
        sources.forEach { source ->
            normalizedTags.forEach { tag ->
                val key = StatisticsTagKey(source, tag)
                this[key] = (this[key] ?: 0L).saturatingAdd(1L)
            }
        }
    }
}

fun Query.searchedTerms(): Set<SearchTerm> = portableTermsForUnified().includeTerms.toSet()

private fun Map<SearchedTagKey, Long>.incrementTerms(sources: Set<SourceKey>, terms: Set<SearchTerm>): Map<SearchedTagKey, Long> {
    val normalizedTerms = terms.mapNotNullTo(linkedSetOf(), SearchTerm::normalizedForCountOrNull)
    if (normalizedTerms.isEmpty() || sources.isEmpty()) return this
    return toMutableMap().apply {
        sources.forEach { source ->
            normalizedTerms.forEach { term ->
                val key = SearchedTagKey(source, term)
                this[key] = (this[key] ?: 0L).saturatingAdd(1L)
            }
        }
    }
}

private fun SearchTerm.normalizedForCountOrNull(): SearchTerm? {
    val normalizedValue = value.trim().takeIf(String::isNotBlank) ?: return null
    return copy(value = normalizedValue, sourceNamespace = sourceNamespace?.trim()?.lowercase()?.takeIf(String::isNotBlank))
}

private fun decodeSearchedTagCounts(
    general: List<StatisticsTagCountRecord>,
    typed: List<StatisticsTypedTagCountRecord>,
): Map<SearchedTagKey, Long> {
    val counts = general.decodeTagCounts().mapKeysTo(linkedMapOf()) { (key, _) -> SearchedTagKey(key.source, SearchTerm(key.tag)) }
    typed.forEach { record ->
        val source = SourceKey.parse(record.source) ?: return@forEach
        val facet = SearchFacet.entries.firstOrNull { it.name == record.facet } ?: return@forEach
        val term = SearchTerm(record.tag, facet, record.sourceNamespace).normalizedForCountOrNull() ?: return@forEach
        if (record.count <= 0L) return@forEach
        val key = SearchedTagKey(source, term)
        counts[key] = (counts[key] ?: 0L).saturatingAdd(record.count)
    }
    return counts
}

private fun Map<SearchedTagKey, Long>.encodeTypedTagCounts(): List<StatisticsTypedTagCountRecord> =
    entries.sortedWith(compareBy({ it.key.source.id }, { it.key.term.facet }, { it.key.term.sourceNamespace }, { it.key.term.value }))
        .map { (key, count) ->
            StatisticsTypedTagCountRecord(key.source.id, key.term.value, key.term.facet.name, key.term.sourceNamespace, count)
        }

private fun Map<SearchedTagKey, Long>.normalizeSearchedTagCounts(): Map<SearchedTagKey, Long> =
    entries.mapNotNull { (key, count) ->
        val term = key.term.normalizedForCountOrNull() ?: return@mapNotNull null
        val normalizedCount = count.coerceAtLeast(0L).takeIf { it > 0L } ?: return@mapNotNull null
        SearchedTagKey(key.source, term) to normalizedCount
    }.toMap()

private fun List<StatisticsTagCountRecord>.decodeTagCounts(): Map<StatisticsTagKey, Long> {
    val tags = linkedMapOf<StatisticsTagKey, Long>()
    forEach { record ->
        val source = SourceKey.parse(record.source) ?: return@forEach
        val tag = record.tag.trim().takeIf(String::isNotBlank) ?: return@forEach
        if (record.count <= 0L) return@forEach
        val key = StatisticsTagKey(source, tag)
        tags[key] = (tags[key] ?: 0L).saturatingAdd(record.count)
    }
    return tags
}

private fun Map<StatisticsTagKey, Long>.encodeTagCounts(): List<StatisticsTagCountRecord> =
    entries.sortedWith(compareBy({ it.key.source.id }, { it.key.tag }))
        .map { (key, count) -> StatisticsTagCountRecord(key.source.id, key.tag, count) }

private fun Map<StatisticsTagKey, Long>.normalizeTagCounts(): Map<StatisticsTagKey, Long> =
    entries.mapNotNull { (key, count) ->
        val tag = key.tag.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val normalizedCount = count.coerceAtLeast(0L).takeIf { it > 0L } ?: return@mapNotNull null
        StatisticsTagKey(key.source, tag) to normalizedCount
    }.toMap()
