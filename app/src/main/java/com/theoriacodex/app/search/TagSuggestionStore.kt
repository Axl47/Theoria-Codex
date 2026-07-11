package com.theoriacodex.app.search

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TagSuggestionStore {
    /**
     * Compatibility lane for callers that only understand portable, general tags.
     */
    fun get(source: SourceKey, limit: Int): List<TagSuggestion>
    fun put(source: SourceKey, suggestions: List<TagSuggestion>)

    suspend fun awaitLoaded() = Unit
    suspend fun flush() = Unit
    suspend fun close() = Unit
    fun requestClose() = Unit

    fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope = FacetedSearchScope.All,
    ): List<FacetedTagSuggestion> {
        if (!scope.isAll && (scope.facet != SearchFacet.TAG || scope.sourceNamespace != null)) {
            return emptyList()
        }
        return get(source, limit).map { suggestion ->
            FacetedTagSuggestion(
                text = suggestion.text,
                facet = SearchFacet.TAG,
                sourceNamespace = null,
                count = suggestion.count,
            )
        }
    }

    fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) {
        put(
            source = source,
            suggestions = suggestions
                .filter { suggestion ->
                    suggestion.facet == SearchFacet.TAG && suggestion.sourceNamespace == null
                }
                .map { suggestion ->
                    TagSuggestion(
                        text = suggestion.text,
                        type = null,
                        count = suggestion.count,
                    )
                },
        )
    }
}

object NoOpTagSuggestionStore : TagSuggestionStore {
    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> = emptyList()
    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) = Unit

    override fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> = emptyList()

    override fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) = Unit
}

internal class FileBackedTagSuggestionStore(
    private val storeFile: File,
    private val seedData: Map<SourceKey, List<TagSuggestion>> = emptyMap(),
    private val maxEntriesPerSource: Int = DEFAULT_MAX_ENTRIES_PER_SOURCE,
    private val maxTotalUtf8Bytes: Int = DEFAULT_MAX_TOTAL_UTF8_BYTES,
    private val persistenceDebounceMs: Long = DEFAULT_PERSISTENCE_DEBOUNCE_MS,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
    private val fileStore: AtomicJsonFileStore = AtomicJsonFileStore(gson = gson),
    private val onWriteCompleted: () -> Unit = {},
) : TagSuggestionStore {
    private val lock = Any()
    private val persistenceMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + workDispatcher)
    private val inMemory: MutableMap<SourceKey, MutableList<CachedSuggestion>> = seedInitial()
    private val entryWeights: MutableMap<SourceKey, MutableList<Int>> = mutableMapOf()
    private var estimatedTotalUtf8Bytes = SNAPSHOT_ESTIMATED_OVERHEAD_BYTES
    private var mutationVersion = 0L
    private var persistedVersion = 0L
    private var debounceJob: Job? = null
    private var closing = false
    private var closed = false
    private val loadJob: Job

    init {
        require(maxEntriesPerSource > 0) { "maxEntriesPerSource must be positive" }
        require(maxTotalUtf8Bytes >= SNAPSHOT_ESTIMATED_OVERHEAD_BYTES) {
            "maxTotalUtf8Bytes must fit an empty tag suggestion snapshot"
        }
        require(persistenceDebounceMs >= 0L) { "persistenceDebounceMs must not be negative" }
        rebuildWeightsLocked()
        enforceTotalByteBudgetLocked()
        loadJob = scope.launch {
            if (loadFromDisk()) {
                markDirtyAndSchedulePersistence()
            }
        }
    }

    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            inMemory[source]
                .orEmpty()
                .asSequence()
                .filter(CachedSuggestion::isRecommendationTag)
                .distinctBy { suggestion -> suggestion.text.lowercase(Locale.ROOT) }
                .take(limit)
                .map(CachedSuggestion::toLegacySuggestion)
                .toList()
        }
    }

    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) {
        putCached(
            source = source,
            suggestions = suggestions.mapNotNull { suggestion ->
                CachedSuggestion.fromLegacySuggestion(source, suggestion)
            },
        )
    }

    override fun getFaceted(
        source: SourceKey,
        limit: Int,
        scope: FacetedSearchScope,
    ): List<FacetedTagSuggestion> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            inMemory[source]
                .orEmpty()
                .asSequence()
                .filter { suggestion -> suggestion.matches(scope) }
                .take(limit)
                .map(CachedSuggestion::toFacetedSuggestion)
                .toList()
        }
    }

    override fun putFaceted(source: SourceKey, suggestions: List<FacetedTagSuggestion>) {
        putCached(
            source = source,
            suggestions = suggestions.mapNotNull(CachedSuggestion::fromFacetedSuggestion),
        )
    }

    private fun putCached(source: SourceKey, suggestions: List<CachedSuggestion>) {
        if (suggestions.isEmpty()) return
        synchronized(lock) {
            check(!closing && !closed) { "Tag suggestion store is closing or closed" }
            val merged = mergeByIdentity(
                existing = inMemory[source].orEmpty(),
                incoming = suggestions,
            )
            replaceSourceLocked(source, merged.take(maxEntriesPerSource))
            enforceTotalByteBudgetLocked()
            mutationVersion += 1L
            schedulePersistenceLocked()
        }
    }

    override suspend fun awaitLoaded() {
        loadJob.join()
    }

    override suspend fun flush() {
        loadJob.join()
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
        }
        while (true) {
            val hasPending = synchronized(lock) { persistedVersion < mutationVersion }
            if (!hasPending) return
            persistPending()
        }
    }

    override suspend fun close() {
        synchronized(lock) {
            if (closed) return
            closing = true
        }
        try {
            withContext(NonCancellable) {
                flush()
            }
        } finally {
            synchronized(lock) {
                closed = true
                debounceJob?.cancel()
                debounceJob = null
            }
            scope.cancel()
        }
    }

    override fun requestClose() {
        val shouldClose = synchronized(lock) {
            if (closing || closed) {
                false
            } else {
                closing = true
                true
            }
        }
        if (shouldClose) {
            scope.launch {
                close()
            }
        }
    }

    private fun seedInitial(): MutableMap<SourceKey, MutableList<CachedSuggestion>> {
        return seedData.mapValues { (source, values) ->
            mergeByIdentity(
                existing = emptyList(),
                incoming = values.mapNotNull { suggestion ->
                    CachedSuggestion.fromLegacySuggestion(source, suggestion)
                },
            )
                .take(maxEntriesPerSource)
                .toMutableList()
        }.toMutableMap()
    }

    private suspend fun loadFromDisk(): Boolean {
        val snapshot = fileStore.read(storeFile, TagStoreSnapshot())
        var pruned = false
        synchronized(lock) {
            snapshot.sources.orEmpty().forEach { (sourceName, entries) ->
                val source = runCatching { SourceKey.valueOf(sourceName) }.getOrNull() ?: return@forEach
                val parsed = entries.orEmpty().mapNotNull(CachedSuggestion::fromStoreEntry)
                    .take(maxEntriesPerSource)
                val current = inMemory[source].orEmpty()
                replaceSourceLocked(
                    source = source,
                    suggestions = mergeByIdentity(existing = parsed, incoming = current)
                        .take(maxEntriesPerSource),
                )
            }
            pruned = enforceTotalByteBudgetLocked()
        }
        return pruned
    }

    private fun markDirtyAndSchedulePersistence() {
        synchronized(lock) {
            if (closed) return
            mutationVersion += 1L
            schedulePersistenceLocked()
        }
    }

    private fun schedulePersistenceLocked() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(persistenceDebounceMs)
            loadJob.join()
            try {
                persistPending()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the dirty version pending so flush or the next mutation can retry it.
            }
        }
    }

    private suspend fun persistPending() {
        persistenceMutex.withLock {
            val pending = synchronized(lock) {
                if (persistedVersion >= mutationVersion) return
                snapshotLocked() to mutationVersion
            }
            fileStore.write(storeFile, pending.first)
            synchronized(lock) {
                persistedVersion = maxOf(persistedVersion, pending.second)
            }
            onWriteCompleted()
        }
    }

    private fun enforceTotalByteBudgetLocked(): Boolean {
        var pruned = false
        while (estimatedTotalUtf8Bytes > maxTotalUtf8Bytes) {
            val sourceToTrim = inMemory.entries
                .asSequence()
                .filter { (_, values) -> values.isNotEmpty() }
                .maxWithOrNull(
                    compareBy<Map.Entry<SourceKey, MutableList<CachedSuggestion>>> { (_, values) ->
                        values.size
                    }.thenBy { (source, _) -> source.name },
                )
                ?.key
                ?: break
            val suggestions = inMemory.getValue(sourceToTrim)
            val weights = entryWeights.getValue(sourceToTrim)
            suggestions.removeAt(suggestions.lastIndex)
            estimatedTotalUtf8Bytes -= weights.removeAt(weights.lastIndex)
            if (suggestions.isEmpty()) {
                inMemory.remove(sourceToTrim)
                entryWeights.remove(sourceToTrim)
                estimatedTotalUtf8Bytes -= sourceEstimatedOverhead(sourceToTrim)
            }
            pruned = true
        }
        return pruned
    }

    private fun snapshotLocked(): TagStoreSnapshot {
        val sources = inMemory.entries
            .asSequence()
            .filter { (_, values) -> values.isNotEmpty() }
            .sortedBy { (source, _) -> source.name }
            .associate { (source, values) ->
                source.name to values.map(CachedSuggestion::toStoreEntry)
            }
        return TagStoreSnapshot(sources = sources)
    }

    private fun rebuildWeightsLocked() {
        val seeded = inMemory.mapValues { (_, suggestions) -> suggestions.toList() }
        inMemory.clear()
        entryWeights.clear()
        estimatedTotalUtf8Bytes = SNAPSHOT_ESTIMATED_OVERHEAD_BYTES
        seeded.forEach { (source, suggestions) ->
            replaceSourceLocked(source, suggestions)
        }
    }

    private fun replaceSourceLocked(source: SourceKey, suggestions: List<CachedSuggestion>) {
        val previousSuggestions = inMemory[source].orEmpty()
        val previousWeights = entryWeights[source].orEmpty()
        if (previousSuggestions.isNotEmpty()) {
            estimatedTotalUtf8Bytes -= sourceEstimatedOverhead(source) + previousWeights.sum()
        }

        if (suggestions.isEmpty()) {
            inMemory.remove(source)
            entryWeights.remove(source)
            return
        }

        val previousByIdentity = previousSuggestions.mapIndexed { index, suggestion ->
            suggestion.identity() to (suggestion to previousWeights.getOrNull(index))
        }.toMap()
        val replacementWeights: List<Int> = suggestions.map { suggestion ->
            val retainedWeight = previousByIdentity[suggestion.identity()]
                ?.takeIf { (previous, weight) -> previous == suggestion && weight != null }
                ?.second
            retainedWeight ?: estimatedEntryBytes(suggestion)
        }
        inMemory[source] = suggestions.toMutableList()
        entryWeights[source] = replacementWeights.toMutableList()
        estimatedTotalUtf8Bytes += sourceEstimatedOverhead(source) + replacementWeights.sum()
    }

    private fun estimatedEntryBytes(suggestion: CachedSuggestion): Int {
        return gson.toJson(suggestion.toStoreEntry()).toByteArray(Charsets.UTF_8).size +
            ENTRY_ESTIMATED_FORMATTING_OVERHEAD_BYTES
    }

    private fun sourceEstimatedOverhead(source: SourceKey): Int {
        return source.name.toByteArray(Charsets.UTF_8).size + SOURCE_ESTIMATED_OVERHEAD_BYTES
    }
}

private data class TagStoreSnapshot(
    @field:SerializedName("sources")
    val sources: Map<String, List<TagStoreEntry>>? = emptyMap(),
)

private data class TagStoreEntry(
    @field:SerializedName("text")
    val text: String? = null,
    @field:SerializedName("facet")
    val facet: String? = null,
    @field:SerializedName("sourceNamespace")
    val sourceNamespace: String? = null,
    @field:SerializedName("type")
    val type: String? = null,
    @field:SerializedName("count")
    val count: Int? = null,
)

private data class CachedSuggestion(
    val text: String,
    val facet: SearchFacet,
    val sourceNamespace: String?,
    val type: String?,
    val count: Int?,
) {
    val isRecommendationTag: Boolean
        get() = facet == SearchFacet.TAG

    fun matches(scope: FacetedSearchScope): Boolean {
        if (scope.isAll) return true
        if (scope.facet != facet) return false
        return scope.sourceNamespace == null || scope.sourceNamespace == sourceNamespace
    }

    fun toLegacySuggestion(): TagSuggestion {
        return TagSuggestion(text = text, type = type, count = count)
    }

    fun toFacetedSuggestion(): FacetedTagSuggestion {
        return FacetedTagSuggestion(
            text = text,
            facet = facet,
            sourceNamespace = sourceNamespace,
            count = count,
        )
    }

    fun toStoreEntry(): TagStoreEntry {
        return TagStoreEntry(
            text = text,
            facet = facet.name,
            sourceNamespace = sourceNamespace,
            type = type,
            count = count,
        )
    }

    companion object {
        fun fromLegacySuggestion(source: SourceKey, suggestion: TagSuggestion): CachedSuggestion? {
            val taxonomy = legacySuggestionTaxonomy(source, suggestion.type)
            return create(
                text = suggestion.text,
                facet = taxonomy.first,
                sourceNamespace = taxonomy.second,
                type = suggestion.type,
                count = suggestion.count,
            )
        }

        fun fromFacetedSuggestion(suggestion: FacetedTagSuggestion): CachedSuggestion? {
            return create(
                text = suggestion.text,
                facet = suggestion.facet,
                sourceNamespace = suggestion.sourceNamespace,
                type = null,
                count = suggestion.count,
            )
        }

        fun fromStoreEntry(entry: TagStoreEntry): CachedSuggestion? {
            val facet = when {
                entry.facet == null -> SearchFacet.TAG
                else -> runCatching {
                    SearchFacet.valueOf(entry.facet.uppercase(Locale.ROOT))
                }.getOrNull() ?: return null
            }
            return create(
                text = entry.text.orEmpty(),
                facet = facet,
                sourceNamespace = entry.sourceNamespace,
                type = entry.type,
                count = entry.count,
            )
        }

        private fun create(
            text: String,
            facet: SearchFacet,
            sourceNamespace: String?,
            type: String?,
            count: Int?,
        ): CachedSuggestion? {
            val normalizedText = text.trim().takeIf(String::isNotBlank) ?: return null
            return CachedSuggestion(
                text = normalizedText,
                facet = facet,
                sourceNamespace = sourceNamespace
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf(String::isNotBlank),
                type = type,
                count = count,
            )
        }
    }
}

private fun legacySuggestionTaxonomy(source: SourceKey, type: String?): Pair<SearchFacet, String?> {
    if (source != SourceKey.NHENTAI) return SearchFacet.TAG to null
    val namespace = type?.trim()?.lowercase(Locale.ROOT)
    return when (namespace) {
        "artist" -> SearchFacet.ARTIST to "artist"
        "character" -> SearchFacet.CHARACTER to "character"
        "series" -> SearchFacet.SERIES to "series"
        "parody" -> SearchFacet.SERIES to "parody"
        "group" -> SearchFacet.GROUP to "group"
        "type" -> SearchFacet.TYPE to "type"
        "category" -> SearchFacet.TYPE to "category"
        "language" -> SearchFacet.LANGUAGE to "language"
        "female", "male" -> SearchFacet.TAG to namespace
        "tag" -> SearchFacet.TAG to "tag"
        else -> SearchFacet.TAG to null
    }
}

private fun mergeByIdentity(
    existing: List<CachedSuggestion>,
    incoming: List<CachedSuggestion>,
): List<CachedSuggestion> {
    val byKey = linkedMapOf<SuggestionIdentity, CachedSuggestion>()
    (incoming + existing).forEach { suggestion ->
        val key = SuggestionIdentity(
            facet = suggestion.facet,
            sourceNamespace = suggestion.sourceNamespace,
            normalizedValue = suggestion.text.lowercase(Locale.ROOT),
        )
        val previous = byKey[key]
        byKey[key] = when {
            previous == null -> suggestion
            else -> previous.copy(
                type = preferredSuggestionType(primary = previous.type, secondary = suggestion.type),
                count = maxCount(previous.count, suggestion.count),
            )
        }
    }
    return byKey.values.toList()
}

private data class SuggestionIdentity(
    val facet: SearchFacet,
    val sourceNamespace: String?,
    val normalizedValue: String,
)

private fun CachedSuggestion.identity(): SuggestionIdentity {
    return SuggestionIdentity(
        facet = facet,
        sourceNamespace = sourceNamespace,
        normalizedValue = text.lowercase(Locale.ROOT),
    )
}

private fun preferredSuggestionType(primary: String?, secondary: String?): String? {
    val primaryRank = suggestionTypeRank(primary)
    val secondaryRank = suggestionTypeRank(secondary)
    return if (primaryRank >= secondaryRank) primary else secondary
}

private fun suggestionTypeRank(type: String?): Int {
    return when (type?.trim()?.lowercase()) {
        "tag_count_lookup" -> 5
        "trending" -> 4
        "tag" -> 3
        "seen" -> 2
        "pixiv_tags_page" -> 1
        "seed" -> 0
        null, "" -> -1
        else -> 1
    }
}

private fun maxCount(first: Int?, second: Int?): Int? {
    return when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }
}

private const val DEFAULT_MAX_ENTRIES_PER_SOURCE = 25_000
private const val DEFAULT_MAX_TOTAL_UTF8_BYTES = 4 * 1024 * 1024
private const val DEFAULT_PERSISTENCE_DEBOUNCE_MS = 500L
private const val SNAPSHOT_ESTIMATED_OVERHEAD_BYTES = 256
private const val SOURCE_ESTIMATED_OVERHEAD_BYTES = 256
private const val ENTRY_ESTIMATED_FORMATTING_OVERHEAD_BYTES = 128
