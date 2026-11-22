package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.query.QueryHash
import java.util.UUID

/**
 * Replaces each saved For You search (a pinned shelf or a saved mix, [RecentSearchEntry.isRetiredForYouSearch]) with ordinary
 * source searches that Search can replay: one per source it searched, with that source's tags. A mix that
 * searched several sources splits into one search per source, named "<name> · <source>".
 *
 * Ordinary searches always stay exactly as they are. A generated search gives way to them: one whose ID or search
 * an ordinary search (or an earlier generated one) already has is dropped, and a mix's extra searches take only
 * free slots below [MAX_SAVED_SEARCHES]. An entry without usable source tags is dropped. Lists without For You
 * searches come back unchanged, so applying it again is a no-op.
 */
internal fun List<SavedSearchEntry>.withForYouSearchesConverted(
    sourceName: (SourceKey) -> String,
): List<SavedSearchEntry> {
    if (none { it.search.isRetiredForYouSearch }) return this
    val ordinary = filterNot { it.search.isRetiredForYouSearch }
    val ids = ordinary.mapTo(mutableSetOf(), SavedSearchEntry::id)
    val searches = ordinary.mapTo(mutableSetOf()) { it.search.identity() }
    // Null for an ordinary search, which stays in place.
    val generated = map { entry ->
        if (!entry.search.isRetiredForYouSearch) return@map null
        entry.asSourceSearches(sourceName).filter { ids.add(it.id) && searches.add(it.search.identity()) }
    }
    var freeSlots = MAX_SAVED_SEARCHES - ordinary.size - generated.count { !it.isNullOrEmpty() }
    return flatMapIndexed { index, entry ->
        val replacements = generated[index] ?: return@flatMapIndexed listOf(entry)
        val extra = replacements.drop(1).take(freeSlots.coerceAtLeast(0))
        freeSlots -= extra.size
        replacements.take(1) + extra
    }
}

/** What makes two saved searches the same search, as [savedFor] compares them. */
private fun RecentSearchEntry.identity() = queryHash to RecentSearchPayloadCodec.encodeJson(this)

private fun SavedSearchEntry.asSourceSearches(sourceName: (SourceKey) -> String): List<SavedSearchEntry> {
    val bySource = search.sources.mapNotNull { source ->
        search.sourceTags[source]?.filter(String::isNotBlank)?.takeIf(List<String>::isNotEmpty)?.let { source to it }
    }
    return bySource.mapIndexed { index, (source, tags) ->
        val query = Query(QueryMode.Source(source), includeTags = tags, excludeTags = emptyList(),
            sort = search.query.sort, dateRange = null, minScore = null).copy(media = search.query.media)
        SavedSearchEntry(
            // The first search keeps the entry's identity; the others get stable IDs so a re-import replaces them.
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
