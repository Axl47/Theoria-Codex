package com.theoriacodex.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.data.storage.DurableStorePhase
import com.theoriacodex.data.storage.DurableStoreStatus
import com.theoriacodex.data.storage.GsonDataStoreSerializer
import com.theoriacodex.data.storage.UnsupportedStoreSchemaException
import com.theoriacodex.data.storage.preserveCorruptFile
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val DATASTORE_STATISTICS_FILE_NAME = "statistics_store_v1.json"
internal const val STATISTICS_DATASTORE_SCHEMA_VERSION = 1

internal data class StatisticsTagCountRecord(
    @field:SerializedName("source") val source: String = "",
    @field:SerializedName("tag") val tag: String = "",
    @field:SerializedName("count") val count: Long = 0L,
)

internal data class StatisticsStoreRecord(
    @field:SerializedName("appOpenCount") val appOpenCount: Long = 0L,
    @field:SerializedName("totalForegroundMs") val totalForegroundMs: Long = 0L,
    @field:SerializedName("browsingMs") val browsingMs: Long = 0L,
    @field:SerializedName("watchingMs") val watchingMs: Long = 0L,
    @field:SerializedName("codexMs") val codexMs: Long = 0L,
    @field:SerializedName("watchedPostCount") val watchedPostCount: Long = 0L,
    @field:SerializedName("watchedBySource") val watchedBySource: Map<String, Long> = emptyMap(),
    @field:SerializedName("watchedByTag") val watchedByTag: List<StatisticsTagCountRecord> = emptyList(),
    @field:SerializedName("searchCount") val searchCount: Long = 0L,
    @field:SerializedName("searchesBySource") val searchesBySource: Map<String, Long> = emptyMap(),
    @field:SerializedName("forYouSearchCount") val forYouSearchCount: Long = 0L,
    @field:SerializedName("forYouSaveCount") val forYouSaveCount: Long = 0L,
    @field:SerializedName("postUrlCopyCount") val postUrlCopyCount: Long = 0L,
    @field:SerializedName("codexEntryCounts") val codexEntryCounts: Map<String, Long> = emptyMap(),
) {
    fun toDomain(): LifetimeStatistics {
        val tags = linkedMapOf<StatisticsTagKey, Long>()
        watchedByTag.forEach { record ->
            val source = runCatching { SourceKey.valueOf(record.source) }.getOrNull() ?: return@forEach
            val tag = record.tag.trim().takeIf(String::isNotBlank) ?: return@forEach
            if (record.count <= 0L) return@forEach
            val key = StatisticsTagKey(source, tag)
            tags[key] = (tags[key] ?: 0L).saturatingAdd(record.count)
        }
        return StatisticsPolicies.normalize(
            LifetimeStatistics(
                appOpenCount = appOpenCount,
                totalForegroundMs = totalForegroundMs,
                browsingMs = browsingMs,
                watchingMs = watchingMs,
                codexMs = codexMs,
                watchedPostCount = watchedPostCount,
                watchedBySource = watchedBySource.decodeSourceCounts(),
                watchedByTag = tags,
                searchCount = searchCount,
                searchesBySource = searchesBySource.decodeSourceCounts(),
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
        require(codexEntryCounts.values.all { value -> value >= 0L }) {
            "Codex entry counts must be non-negative"
        }
        toDomain()
    }

    companion object {
        fun fromDomain(statistics: LifetimeStatistics): StatisticsStoreRecord {
            val normalized = StatisticsPolicies.normalize(statistics)
            return StatisticsStoreRecord(
                appOpenCount = normalized.appOpenCount,
                totalForegroundMs = normalized.totalForegroundMs,
                browsingMs = normalized.browsingMs,
                watchingMs = normalized.watchingMs,
                codexMs = normalized.codexMs,
                watchedPostCount = normalized.watchedPostCount,
                watchedBySource = normalized.watchedBySource.encodeSourceCounts(),
                watchedByTag = normalized.watchedByTag.entries
                    .sortedWith(compareBy({ it.key.source.name }, { it.key.tag }))
                    .map { (key, count) -> StatisticsTagCountRecord(key.source.name, key.tag, count) },
                searchCount = normalized.searchCount,
                searchesBySource = normalized.searchesBySource.encodeSourceCounts(),
                forYouSearchCount = normalized.forYouSearchCount,
                forYouSaveCount = normalized.forYouSaveCount,
                postUrlCopyCount = normalized.postUrlCopyCount,
                codexEntryCounts = normalized.codexEntryCounts.toSortedMap(),
            )
        }
    }
}

internal data class StatisticsDataStoreFile(
    @field:SerializedName("schemaVersion")
    val schemaVersion: Int = STATISTICS_DATASTORE_SCHEMA_VERSION,
    @field:SerializedName("statistics")
    val statistics: StatisticsStoreRecord = StatisticsStoreRecord(),
) {
    fun toDomain(): LifetimeStatistics = statistics.toDomain()

    fun validate() {
        require(schemaVersion > 0) { "Statistics schema version must be positive" }
        statistics.validate()
    }

    companion object {
        fun fromDomain(statistics: LifetimeStatistics): StatisticsDataStoreFile {
            return StatisticsDataStoreFile(statistics = StatisticsStoreRecord.fromDomain(statistics))
        }
    }
}

internal object StatisticsPolicies {
    fun normalize(statistics: LifetimeStatistics): LifetimeStatistics {
        return statistics.copy(
            appOpenCount = statistics.appOpenCount.coerceAtLeast(0L),
            totalForegroundMs = statistics.totalForegroundMs.coerceAtLeast(0L),
            browsingMs = statistics.browsingMs.coerceAtLeast(0L),
            watchingMs = statistics.watchingMs.coerceAtLeast(0L),
            codexMs = statistics.codexMs.coerceAtLeast(0L),
            watchedPostCount = statistics.watchedPostCount.coerceAtLeast(0L),
            watchedBySource = statistics.watchedBySource.normalizeCounts(),
            watchedByTag = statistics.watchedByTag.entries.mapNotNull { (key, count) ->
                val tag = key.tag.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val normalizedCount = count.coerceAtLeast(0L).takeIf { it > 0L } ?: return@mapNotNull null
                StatisticsTagKey(key.source, tag) to normalizedCount
            }.toMap(),
            searchCount = statistics.searchCount.coerceAtLeast(0L),
            searchesBySource = statistics.searchesBySource.normalizeCounts(),
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

    fun recordAppOpen(current: LifetimeStatistics): LifetimeStatistics {
        return current.copy(appOpenCount = current.appOpenCount.saturatingAdd(1L))
    }

    fun addUsage(current: LifetimeStatistics, delta: UsageDurationDelta): LifetimeStatistics {
        return current.copy(
            totalForegroundMs = current.totalForegroundMs.saturatingAdd(delta.totalMs),
            browsingMs = current.browsingMs.saturatingAdd(delta.browsingMs),
            watchingMs = current.watchingMs.saturatingAdd(delta.watchingMs),
            codexMs = current.codexMs.saturatingAdd(delta.codexMs),
        )
    }

    fun recordWatched(
        current: LifetimeStatistics,
        source: SourceKey,
        tags: Set<String>,
    ): LifetimeStatistics {
        val normalizedTags = tags.mapNotNullTo(linkedSetOf()) { tag ->
            tag.trim().takeIf(String::isNotBlank)
        }
        val sourceCounts = current.watchedBySource.increment(source)
        val tagCounts = current.watchedByTag.toMutableMap()
        normalizedTags.forEach { tag ->
            val key = StatisticsTagKey(source, tag)
            tagCounts[key] = (tagCounts[key] ?: 0L).saturatingAdd(1L)
        }
        return current.copy(
            watchedPostCount = current.watchedPostCount.saturatingAdd(1L),
            watchedBySource = sourceCounts,
            watchedByTag = tagCounts,
        )
    }

    fun recordSearch(current: LifetimeStatistics, sources: Set<SourceKey>): LifetimeStatistics {
        var sourceCounts = current.searchesBySource
        sources.forEach { source -> sourceCounts = sourceCounts.increment(source) }
        return current.copy(
            searchCount = current.searchCount.saturatingAdd(1L),
            searchesBySource = sourceCounts,
        )
    }

    fun recordForYouSearch(current: LifetimeStatistics): LifetimeStatistics {
        return current.copy(forYouSearchCount = current.forYouSearchCount.saturatingAdd(1L))
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

class DataStoreStatisticsRepository(
    baseDirectory: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    gson: Gson = Gson(),
) : StatisticsRepository, AsynchronousStore {
    private val storeFile = baseDirectory.resolve(DATASTORE_STATISTICS_FILE_NAME)
    private val mutableStorageStatus = MutableStateFlow(DurableStoreStatus())
    private val defaultValue = StatisticsDataStoreFile()
    private val dataStore: DataStore<StatisticsDataStoreFile> = DataStoreFactory.create(
        serializer = GsonDataStoreSerializer(
            storeName = DATASTORE_STATISTICS_FILE_NAME,
            defaultValue = defaultValue,
            type = object : TypeToken<StatisticsDataStoreFile>() {}.type,
            gson = gson,
            validate = { stored ->
                if (stored.schemaVersion > STATISTICS_DATASTORE_SCHEMA_VERSION) {
                    throw UnsupportedStoreSchemaException(
                        storeName = DATASTORE_STATISTICS_FILE_NAME,
                        actual = stored.schemaVersion,
                        supported = STATISTICS_DATASTORE_SCHEMA_VERSION,
                    )
                }
                require(stored.schemaVersion == STATISTICS_DATASTORE_SCHEMA_VERSION) {
                    "Unsupported statistics schema ${stored.schemaVersion}"
                }
                stored.validate()
            },
        ),
        corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
            mutableStorageStatus.update { current ->
                current.copy(
                    corruptionRecovery = CorruptionRecovery(
                        reason = corruption.message ?: "Statistics store was corrupt",
                        backupPath = preserveCorruptFile(storeFile),
                    ),
                )
            }
            defaultValue
        },
        scope = scope,
        produceFile = { storeFile },
    )
    private val records = dataStore.data
        .onEach { recordReady() }
        .catch { failure ->
            recordFailure(failure)
            throw failure
        }

    override val storageStatus: StateFlow<DurableStoreStatus> = mutableStorageStatus.asStateFlow()

    override suspend fun awaitReady() {
        records.first()
    }

    override fun observeStatistics(): Flow<LifetimeStatistics> {
        return records.map(StatisticsDataStoreFile::toDomain).distinctUntilChanged()
    }

    override suspend fun recordAppOpen() = mutate(StatisticsPolicies::recordAppOpen)

    override suspend fun addUsageDuration(delta: UsageDurationDelta) = mutate { current ->
        StatisticsPolicies.addUsage(current, delta)
    }

    override suspend fun recordWatchedPost(source: SourceKey, tags: Set<String>) = mutate { current ->
        StatisticsPolicies.recordWatched(current, source, tags)
    }

    override suspend fun recordSearch(sources: Set<SourceKey>) = mutate { current ->
        StatisticsPolicies.recordSearch(current, sources)
    }

    override suspend fun recordForYouSearch() = mutate(StatisticsPolicies::recordForYouSearch)

    override suspend fun recordForYouSave() = mutate(StatisticsPolicies::recordForYouSave)

    override suspend fun recordPostUrlCopy() = mutate(StatisticsPolicies::recordPostUrlCopy)

    override suspend fun recordCodexEntry(codexId: String) = mutate { current ->
        StatisticsPolicies.recordCodexEntry(current, codexId)
    }

    private suspend fun mutate(transform: (LifetimeStatistics) -> LifetimeStatistics) {
        dataStore.updateData { stored ->
            StatisticsDataStoreFile.fromDomain(transform(stored.toDomain()))
        }
    }

    private fun recordReady() {
        mutableStorageStatus.update { current ->
            current.copy(phase = DurableStorePhase.READY, failureReason = null)
        }
    }

    private fun recordFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        mutableStorageStatus.update { current ->
            current.copy(
                phase = DurableStorePhase.FAILED,
                failureReason = failure.message ?: failure::class.simpleName,
            )
        }
    }
}

internal fun Long.saturatingAdd(delta: Long): Long {
    val normalizedDelta = delta.coerceAtLeast(0L)
    return if (this > Long.MAX_VALUE - normalizedDelta) Long.MAX_VALUE else this + normalizedDelta
}

private fun Map<String, Long>.decodeSourceCounts(): Map<SourceKey, Long> {
    return entries.mapNotNull { (name, count) ->
        val source = runCatching { SourceKey.valueOf(name) }.getOrNull() ?: return@mapNotNull null
        val normalizedCount = count.takeIf { it > 0L } ?: return@mapNotNull null
        source to normalizedCount
    }.toMap()
}

private fun Map<SourceKey, Long>.encodeSourceCounts(): Map<String, Long> {
    return entries.sortedBy { it.key.name }.associate { (source, count) -> source.name to count }
}

private fun Map<SourceKey, Long>.normalizeCounts(): Map<SourceKey, Long> {
    return mapValues { (_, count) -> count.coerceAtLeast(0L) }.filterValues { count -> count > 0L }
}

private fun <K> Map<K, Long>.increment(key: K): Map<K, Long> {
    return toMutableMap().apply { this[key] = (this[key] ?: 0L).saturatingAdd(1L) }
}
