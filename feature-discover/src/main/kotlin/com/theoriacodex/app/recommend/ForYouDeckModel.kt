package com.theoriacodex.app.recommend

import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.trails.TrailSpec
import com.theoriacodex.app.triage.TriageActions
import com.theoriacodex.app.triage.TriageDeck
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** What the app gives the For You deck beyond its engine. */
class ForYouDeckWiring(
    val actions: TriageActions,
    val quickSave: QuickSave,
    /**
     * Builds the trail a fork or a like asks for, or null when its source cannot supply one. It is the deck's only way
     * to the app's trails; `animatedOnly` is the deck's own filter, which a tag trail's search keeps.
     */
    val trails: suspend (request: TrailRequest, animatedOnly: Boolean) -> TrailSpec?,
    val relatedLoader: RelatedPostsLoading,
    /** Runs the decks' writes, which must finish after a level, or the owner, is gone. Events arrive on its thread. */
    val writeScope: CoroutineScope,
    val reconnections: Flow<Unit> = emptyFlow(),
    /** Where the posts a like or save asks for are fetched, off the main thread. */
    val fetchContext: CoroutineContext = Dispatchers.Default,
)

/** What the For You route knows that its deck is built from. The route calls the owner again whenever it changes. */
data class ForYouEnvironment(
    val settings: AppSettings,
    val selectedSource: SourceKey?,
    val sortMode: SortMode,
    val animatedOnly: Boolean,
    /** The screen shows the grid, which takes larger deals than the deck. */
    val grid: Boolean,
    /** Posts the profile already liked or saved, which the Mix does not deal again. */
    val kept: Set<PostId>,
    val creatorBrowsingSources: Set<SourceKey>,
)

/** An interest's chip. */
data class ForYouChip(val laneId: String, val label: String)

enum class ForYouMixState {
    /** Lanes are loading or dealing; the deck and the grid say the rest. */
    Dealing,
    NoSources,
    /** The profile's likes and the sources' trending tags gave no lane. */
    NoLanes,
    Failed,
}

data class ForYouDeckUiState(
    val profiles: List<RecommendationProfile> = emptyList(),
    val activeProfileId: String = "",
    val availableSources: List<SourceKey> = emptyList(),
    val selectedSource: SourceKey? = null,
    val chips: List<ForYouChip> = emptyList(),
    /** The chip that narrows the Mix to one lane; null for the whole Mix. */
    val only: String? = null,
    /** One failure per source whose lane could not load, for the status row. */
    val failures: List<SourceRunStatus> = emptyList(),
    val mix: ForYouMixState = ForYouMixState.Dealing,
    val failure: String? = null,
)

/**
 * One level of the For You deck: the Mix, or a trail forked from a card. Each keeps its own deck, so leaving a trail
 * returns to the card the fork started from, with its Undo.
 */
class ForYouLevel internal constructor(
    /** Names the level's feed for the Viewer, which follows it while this level is on top. */
    val id: String,
    /** The crumb. */
    val label: String,
    /** The fork that opened this level; null for the Mix. */
    val fork: TrailRequest?,
    /** A tag trail's search, which Save search saves; null for the Mix and the other trails. */
    val query: Query?,
    val deck: TriageDeck,
    /** The level's posts so far, which the grid shows and the Viewer pages through. */
    val feed: StateFlow<TriageFeedSnapshot>,
    private val source: TriageFeed,
    private val scope: CoroutineScope,
    private val onResolved: (Post) -> Unit,
) {
    fun loadNextPage() = source.loadNextPage()
    fun retryPage() = source.retryPage()

    /** Keeps the fuller copy of a post the Viewer resolved, so the level's feed shows it from now on. */
    internal fun remember(post: Post) = onResolved(post)

    internal fun viewerFeed(feed: TriageFeedSnapshot, related: RelatedPostsUiState) = ViewerFeedSnapshot(
        queryHash = id,
        results = feed.posts,
        canLoadMore = feed.canLoadMore,
        loadingMore = feed.loadingMore,
        pageFailed = feed.pageFailed && !feed.loadingMore,
        relatedPosts = related,
    )

    /** Ends the level's paging; writes its deck already asked for still finish. */
    internal fun close() {
        deck.close()
        scope.cancel()
    }
}

/** What a Mix is built from; a change to any of it starts a fresh Mix. */
internal data class MixInputs(
    val profileId: String,
    val sources: List<SourceKey>,
    val source: SourceKey?,
    val sort: SortMode,
    val animatedOnly: Boolean,
)

internal val LOADING_FEED = TriageFeedSnapshot(emptyList(), canLoadMore = true, loadingMore = true)
