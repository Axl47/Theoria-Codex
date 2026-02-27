package com.theoriacodex.app.search

import com.google.gson.Gson
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import java.io.File

interface TagSuggestionStore {
    fun get(source: SourceKey, limit: Int): List<TagSuggestion>
    fun put(source: SourceKey, suggestions: List<TagSuggestion>)
}

object NoOpTagSuggestionStore : TagSuggestionStore {
    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> = emptyList()
    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) = Unit
}

class FileBackedTagSuggestionStore(
    private val storeFile: File,
    private val seedData: Map<SourceKey, List<TagSuggestion>> = emptyMap(),
    private val maxEntriesPerSource: Int = DEFAULT_MAX_ENTRIES_PER_SOURCE,
    private val gson: Gson = Gson(),
) : TagSuggestionStore {
    private val lock = Any()
    private val inMemory: MutableMap<SourceKey, MutableList<TagSuggestion>> = loadInitial()

    override fun get(source: SourceKey, limit: Int): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            inMemory[source].orEmpty().take(limit)
        }
    }

    override fun put(source: SourceKey, suggestions: List<TagSuggestion>) {
        if (suggestions.isEmpty()) return
        synchronized(lock) {
            val merged = mergeByText(
                existing = inMemory[source].orEmpty(),
                incoming = suggestions,
            )
            inMemory[source] = merged.take(maxEntriesPerSource).toMutableList()
            persistLocked()
        }
    }

    private fun loadInitial(): MutableMap<SourceKey, MutableList<TagSuggestion>> {
        val seeded = seedData.mapValues { (_, values) ->
            mergeByText(existing = emptyList(), incoming = values)
                .take(maxEntriesPerSource)
                .toMutableList()
        }.toMutableMap()
        if (!storeFile.exists()) {
            return seeded
        }
        val snapshot = runCatching {
            gson.fromJson(storeFile.readText(), TagStoreSnapshot::class.java)
        }.getOrNull() ?: return seeded

        snapshot.sources.forEach { (sourceName, entries) ->
            val source = runCatching { SourceKey.valueOf(sourceName) }.getOrNull() ?: return@forEach
            val parsed = entries.mapNotNull { entry ->
                entry.text.trim().takeIf { it.isNotBlank() }?.let { text ->
                    TagSuggestion(text = text, type = entry.type, count = entry.count)
                }
            }
            val existing = seeded[source].orEmpty()
            seeded[source] = mergeByText(existing = existing, incoming = parsed)
                .take(maxEntriesPerSource)
                .toMutableList()
        }
        return seeded
    }

    private fun persistLocked() {
        val snapshot = TagStoreSnapshot(
            sources = inMemory.entries.associate { (source, values) ->
                source.name to values.map { tag ->
                    TagStoreEntry(
                        text = tag.text,
                        type = tag.type,
                        count = tag.count,
                    )
                }
            },
        )
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(gson.toJson(snapshot))
    }
}

private data class TagStoreSnapshot(
    val sources: Map<String, List<TagStoreEntry>> = emptyMap(),
)

private data class TagStoreEntry(
    val text: String,
    val type: String? = null,
    val count: Int? = null,
)

private fun mergeByText(
    existing: List<TagSuggestion>,
    incoming: List<TagSuggestion>,
): List<TagSuggestion> {
    val byKey = linkedMapOf<String, TagSuggestion>()
    (incoming + existing).forEach { suggestion ->
        val key = suggestion.text.trim().lowercase()
        if (key.isBlank()) return@forEach
        val previous = byKey[key]
        val normalized = suggestion.copy(text = suggestion.text.trim())
        byKey[key] = when {
            previous == null -> normalized
            else -> previous.copy(
                type = preferredSuggestionType(primary = previous.type, secondary = normalized.type),
                count = maxCount(previous.count, normalized.count),
            )
        }
    }
    return byKey.values.toList()
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
