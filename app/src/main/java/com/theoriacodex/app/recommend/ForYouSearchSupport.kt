package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey

internal fun forYouSourceQuery(
    source: SourceKey,
    includeTags: List<String>,
    sortMode: SortMode,
): Query = Query(
    mode = QueryMode.Source(source),
    includeTags = includeTags,
    excludeTags = emptyList(),
    sort = sortMode,
    dateRange = null,
    minScore = null,
)

internal fun buildForYouSeedId(seed: Map<SourceKey, List<String>>): String {
    if (seed.isEmpty()) return "none"
    return seed.entries
        .sortedBy { it.key.name }
        .joinToString(separator = "|") { (source, tags) ->
            "${source.name}:${tags.joinToString(separator = "+")}"
        }
}
