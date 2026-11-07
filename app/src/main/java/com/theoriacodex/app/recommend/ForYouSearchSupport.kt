package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.CrossSourceTraining
import com.theoriacodex.domain.recommendation.LikedTagDocument
import com.theoriacodex.domain.recommendation.PixivTagDictionary

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

/** Lets Likes on one tag-compatible source train the others, using learned Pixiv translations. */
internal suspend fun crossSourceTraining(likes: List<LikedPost>, store: TagSuggestionStore): CrossSourceTraining {
    store.awaitLoaded()
    val pixivPairs = store.get(SourceKey.PIXIV, Int.MAX_VALUE)
        .mapNotNull { suggestion -> suggestion.alternateText?.let { suggestion.text to it } }
    return CrossSourceTraining(
        likes = likes.map { liked -> LikedTagDocument(liked.postId.source, liked.tags) },
        dictionary = PixivTagDictionary.from(pixivPairs),
    )
}

internal fun buildForYouSeedId(seed: Map<SourceKey, List<String>>): String {
    if (seed.isEmpty()) return "none"
    return seed.entries
        .sortedBy { it.key.name }
        .joinToString(separator = "|") { (source, tags) ->
            "${source.name}:${tags.joinToString(separator = "+")}"
        }
}
