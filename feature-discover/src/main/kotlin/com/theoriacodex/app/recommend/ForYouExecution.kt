package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
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
    val excludedSectionIds: Set<String> = emptySet(),
    val additionalSection: Boolean = false,
)

data class ForYouFeedResult(
    val seed: Map<SourceKey, List<String>>,
    val seedId: String,
    val likesCount: Int,
    val page: UnifiedSearchResult,
    val continuation: FeedCursor?,
) {
    /** Names this feed for the Viewer and the triage deck while it is the grid's feed. */
    val queryHash: String get() = forYouQueryHash(seedId)

    companion object {
        fun empty(seedId: String, likesCount: Int): ForYouFeedResult = ForYouFeedResult(
            emptyMap(), seedId, likesCount, UnifiedSearchResult(emptyList(), emptyMap(), emptyList()), null,
        )
    }
}

/** The key the Viewer and the triage deck find the For You feed by while it shows seed [seedId]. */
internal fun forYouQueryHash(seedId: String): String = "for_you:$seedId"

data class ForYouBlacklistChange(val settings: AppSettings, val entries: List<ForYouBlacklistEntry>)

interface ForYouRouteEngine {
    suspend fun loadSections(request: ForYouFeedRequest): List<ForYouSection> = emptyList()
    suspend fun pinSection(section: ForYouSection, pinned: Boolean): String? = error("Section pins unavailable")
    fun availableSources(settings: AppSettings): List<SourceKey>
    suspend fun execute(request: ForYouFeedRequest): ForYouFeedResult
    suspend fun loadPage(continuation: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult
    suspend fun updateBlacklist(profileId: String, entries: List<ForYouBlacklistEntry>, hidden: Boolean): ForYouBlacklistChange
    suspend fun recordAccepted(result: ForYouFeedResult)
    suspend fun resolvePost(postId: PostId): Post?
}

internal fun forYouBaseQuery(sort: SortMode): Query = Query(
    mode = QueryMode.Unified, includeTags = emptyList(), excludeTags = emptyList(),
    sort = sort, dateRange = null, minScore = null,
)
