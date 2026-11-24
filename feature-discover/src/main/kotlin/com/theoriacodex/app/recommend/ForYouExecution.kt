package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchResult

data class ForYouFeedRequest(
    val settings: AppSettings,
    val availableSources: List<SourceKey>,
    val selectedSource: SourceKey?,
    val sortMode: SortMode,
    val animatedOnly: Boolean = false,
)

data class ForYouLane(val source: SourceKey, val tags: List<String>, val cursor: FeedCursor) {
    val id: String get() = "${source.id}:${tags.sorted().joinToString("+")}"

    val label: String get() = tags.joinToString(" + ") { it.replace('_', ' ') }
}

interface ForYouRouteEngine {
    fun availableSources(settings: AppSettings): List<SourceKey>

    suspend fun lanes(request: ForYouFeedRequest): List<ForYouLane>

    suspend fun loadPage(continuation: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult
    suspend fun resolvePost(postId: PostId): Post?

    suspend fun lastDeck(request: ForYouFeedRequest): List<Post> = emptyList()

    suspend fun rememberDeck(request: ForYouFeedRequest, posts: List<Post>) = Unit
}

internal fun ForYouFeedRequest.deckKey(): String =
    listOf(settings.activeProfileId, selectedSource?.id.orEmpty(), sortMode.name, animatedOnly.toString()).joinToString("|")
