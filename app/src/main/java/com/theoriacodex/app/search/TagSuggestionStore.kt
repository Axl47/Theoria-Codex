package com.theoriacodex.app.search

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import java.io.File
import java.util.Locale

interface TagSuggestionStore {
    /**
     * Compatibility lane for callers that only understand portable, general tags.
     */
    fun get(source: SourceKey, limit: Int): List<TagSuggestion>
    fun put(source: SourceKey, suggestions: List<TagSuggestion>)

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

class FileBackedTagSuggestionStore(
    private val storeFile: File,
    private val seedData: Map<SourceKey, List<TagSuggestion>> = emptyMap(),
    private val maxEntriesPerSource: Int = DEFAULT_MAX_ENTRIES_PER_SOURCE,
    private val gson: Gson = Gson(),
) : TagSuggestionStore {
    private val lock = Any()
    private val inMemory: MutableMap<SourceKey, MutableList<CachedSuggestion>> = loadInitial()

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
            val merged = mergeByIdentity(
                existing = inMemory[source].orEmpty(),
                incoming = suggestions,
            )
            inMemory[source] = merged.take(maxEntriesPerSource).toMutableList()
            persistLocked()
        }
    }

    private fun loadInitial(): MutableMap<SourceKey, MutableList<CachedSuggestion>> {
        val seeded = seedData.mapValues { (source, values) ->
            mergeByIdentity(
                existing = emptyList(),
                incoming = values.mapNotNull { suggestion ->
                    CachedSuggestion.fromLegacySuggestion(source, suggestion)
                },
            )
                .take(maxEntriesPerSource)
                .toMutableList()
        }.toMutableMap()
        if (!storeFile.exists()) {
            return seeded
        }
        val snapshot = runCatching {
            gson.fromJson(storeFile.readText(), TagStoreSnapshot::class.java)
        }.getOrNull() ?: return seeded

        snapshot.sources.orEmpty().forEach { (sourceName, entries) ->
            val source = runCatching { SourceKey.valueOf(sourceName) }.getOrNull() ?: return@forEach
            val parsed = entries.orEmpty().mapNotNull(CachedSuggestion::fromStoreEntry)
            val existing = seeded[source].orEmpty()
            seeded[source] = mergeByIdentity(existing = existing, incoming = parsed)
                .take(maxEntriesPerSource)
                .toMutableList()
        }
        return seeded
    }

    private fun persistLocked() {
        val snapshot = TagStoreSnapshot(
            sources = inMemory.entries.associate { (source, values) ->
                source.name to values.map(CachedSuggestion::toStoreEntry)
            },
        )
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(gson.toJson(snapshot))
    }
}

private data class TagStoreSnapshot(
    val sources: Map<String, List<TagStoreEntry>>? = emptyMap(),
)

private data class TagStoreEntry(
    val text: String? = null,
    val facet: String? = null,
    val sourceNamespace: String? = null,
    val type: String? = null,
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
