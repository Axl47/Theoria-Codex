package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.TagSuggestionStore
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

internal suspend fun crossSourceTraining(likes: List<LikedTagDocument>, store: TagSuggestionStore): CrossSourceTraining {
    store.awaitLoaded()
    val pixivPairs = store.get(SourceKey.PIXIV, Int.MAX_VALUE)
        .mapNotNull { suggestion -> suggestion.alternateText?.let { suggestion.text to it } }
    return CrossSourceTraining(
        likes = likes,
        dictionary = PixivTagDictionary.from(pixivPairs),
    )
}
