package com.theoriacodex.app.recommend

import com.theoriacodex.domain.model.Post
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

internal fun mergeForYouResults(current: List<Post>, next: List<Post>): List<Post> {
    if (next.isEmpty()) return current
    if (current.isEmpty()) return next

    val seen = current.mapTo(mutableSetOf()) { post ->
        "${post.id.source.name}:${post.id.sourcePostId}"
    }
    return current.toMutableList().apply {
        next.forEach { post ->
            val key = "${post.id.source.name}:${post.id.sourcePostId}"
            if (seen.add(key)) add(post)
        }
    }
}

internal fun buildForYouSeedId(seed: Map<SourceKey, List<String>>): String {
    if (seed.isEmpty()) return "none"
    return seed.entries
        .sortedBy { it.key.name }
        .joinToString(separator = "|") { (source, tags) ->
            "${source.name}:${tags.joinToString(separator = "+")}"
        }
}
