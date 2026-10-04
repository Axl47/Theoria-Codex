package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.settleFeedContinuations
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchResult

data class ForYouFeedRequest(
    val settings: AppSettings,
    val availableSources: List<SourceKey>,
    val selectedSource: SourceKey?,
    val sortMode: SortMode,
    val shuffle: Boolean,
    val likesCount: Int,
    val historicalSeed: Map<SourceKey, List<String>>? = null,
)

data class ForYouContinuation(
    val query: Query,
    val queriesBySource: Map<SourceKey, Query>,
    val weights: Map<SourceKey, Double>,
    val pageTokens: Map<SourceKey, String?>,
) {
    fun accept(page: UnifiedSearchResult, attempted: Set<SourceKey>): ForYouContinuation = copy(
        queriesBySource = queriesBySource + page.queriesBySource,
        pageTokens = settleFeedContinuations(pageTokens, attempted, page.nextPageTokens, page.statuses),
    )
}

data class ForYouFeedResult(
    val seed: Map<SourceKey, List<String>>,
    val seedId: String,
    val likesCount: Int,
    val page: UnifiedSearchResult,
    val continuation: ForYouContinuation?,
) {
    companion object {
        fun empty(seedId: String, likesCount: Int): ForYouFeedResult = ForYouFeedResult(
            emptyMap(), seedId, likesCount, UnifiedSearchResult(emptyList(), emptyMap(), emptyList()), null,
        )
    }
}

data class ForYouBlacklistChange(val settings: AppSettings, val entries: List<ForYouBlacklistEntry>)

interface ForYouRouteEngine {
    fun availableSources(settings: AppSettings): List<SourceKey>
    suspend fun execute(request: ForYouFeedRequest): ForYouFeedResult
    suspend fun loadPage(continuation: ForYouContinuation, sources: Set<SourceKey>): UnifiedSearchResult
    suspend fun updateBlacklist(profileId: String, entries: List<ForYouBlacklistEntry>, hidden: Boolean): ForYouBlacklistChange
    suspend fun recordAccepted(result: ForYouFeedResult)
    suspend fun resolvePost(postId: PostId): Post?
}

internal fun forYouBaseQuery(sort: SortMode): Query = Query(
    mode = QueryMode.Unified, includeTags = emptyList(), excludeTags = emptyList(),
    sort = sort, dateRange = null, minScore = null,
)
