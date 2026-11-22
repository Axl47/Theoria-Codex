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
 * searched several sources splits into one search per source, named "<name> · <source>", and the extra searches
 * take only free slots below [MAX_SAVED_SEARCHES], so existing searches always stay. An entry without usable
 * source tags is dropped, and a converted search the list already holds is kept once. Lists without For You
 * searches come back unchanged, so applying it again is a no-op.
 */
internal fun List<SavedSearchEntry>.withForYouSearchesConverted(
    sourceName: (SourceKey) -> String,
): List<SavedSearchEntry> {
    if (none { it.search.isRetiredForYouSearch }) return this
    val replacements = map { entry -> if (entry.search.isRetiredForYouSearch) entry.asSourceSearches(sourceName) else listOf(entry) }
    var freeSlots = MAX_SAVED_SEARCHES - replacements.count(List<SavedSearchEntry>::isNotEmpty)
    return replacements.flatMap { searches ->
        val extra = searches.drop(1).take(freeSlots.coerceAtLeast(0))
        freeSlots -= extra.size
        searches.take(1) + extra
    }.distinctBy(SavedSearchEntry::id).distinctBy { it.search.queryHash to RecentSearchPayloadCodec.encodeJson(it.search) }
}

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
