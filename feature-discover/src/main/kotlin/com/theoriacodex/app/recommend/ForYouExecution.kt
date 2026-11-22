package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchResult

/** What a Mix is built for: the active profile's settings, the sources that can run, and the bar's choices. */
data class ForYouFeedRequest(
    val settings: AppSettings,
    val availableSources: List<SourceKey>,
    val selectedSource: SourceKey?,
    val sortMode: SortMode,
    val animatedOnly: Boolean = false,
)

/**
 * One feed the Mix draws from: an interest of the profile on one source, or a trending tag for a profile with no
 * likes. [cursor] opens its first page through [ForYouRouteEngine.loadPage] and is folded forward with each page.
 */
data class ForYouLane(val source: SourceKey, val tags: List<String>, val cursor: FeedCursor) {
    val id: String get() = buildForYouSeedId(mapOf(source to tags.sorted()))

    /** "fog + sea": what the lane's chip and its cards say. */
    val label: String get() = tags.joinToString(" + ") { it.replace('_', ' ') }
}

/** What the For You deck needs from the sources: the profile's lanes and their pages. */
interface ForYouRouteEngine {
    fun availableSources(settings: AppSettings): List<SourceKey>

    /** The lanes of a fresh Mix, none of them hidden by the profile's hidden tags; empty when there is nothing to show. */
    suspend fun lanes(request: ForYouFeedRequest): List<ForYouLane>

    suspend fun loadPage(continuation: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult
    suspend fun resolvePost(postId: PostId): Post?
}
