package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.QueryHash
import java.util.UUID

internal fun List<SavedSearchEntry>.withForYouSearchesConverted(
    sourceName: (SourceKey) -> String,
): List<SavedSearchEntry> {
    if (none { it.search.isRetiredForYouSearch }) return this
    val ordinary = filterNot { it.search.isRetiredForYouSearch }
    val ids = ordinary.mapTo(mutableSetOf(), SavedSearchEntry::id)
    val searches = ordinary.mapTo(mutableSetOf()) { it.search.identity() }
    val generated = map { entry ->
        if (!entry.search.isRetiredForYouSearch) return@map null
        entry.asSourceSearches(sourceName).filter { ids.add(it.id) && searches.add(it.search.identity()) }
    }
    var freeSlots = MAX_SAVED_SEARCHES - ordinary.size - indices.count { index -> generated[index]?.any { it.id == this[index].id } == true }
    return flatMapIndexed { index, entry ->
        val (own, others) = (generated[index] ?: return@flatMapIndexed listOf(entry)).partition { it.id == entry.id }
        val extra = others.take(freeSlots.coerceAtLeast(0))
        freeSlots -= extra.size
        own + extra
    }
}

private fun RecentSearchEntry.identity() = queryHash to RecentSearchPayloadCodec.encodeJson(this)

private fun SavedSearchEntry.asSourceSearches(sourceName: (SourceKey) -> String): List<SavedSearchEntry> {
    val bySource = search.sources.mapNotNull { source ->
        search.sourceTags[source]?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)?.let { source to it }
    }
    return bySource.mapIndexed { index, (source, tags) ->
        val query = Query(QueryMode.Source(source), includeTags = tags, excludeTags = emptyList(),
            sort = search.query.sort, dateRange = null, minScore = null).copy(media = search.query.media)
        SavedSearchEntry(
            id = if (index == 0) id else UUID.nameUUIDFromBytes("$id:${source.id}".toByteArray()).toString(),
            name = if (bySource.size == 1) name else splitName(name, sourceName(source)),
            search = RecentSearchEntry(query, QueryHash.from(query), search.searchedAtEpochMs),
            savedAtEpochMs = savedAtEpochMs,
        )
    }
}

private fun splitName(name: String, source: String): String {
    val suffix = " · $source"
    return (name.take((MAX_SAVED_SEARCH_NAME_LENGTH - suffix.length).coerceAtLeast(1)).trimEnd() + suffix)
        .take(MAX_SAVED_SEARCH_NAME_LENGTH)
}
