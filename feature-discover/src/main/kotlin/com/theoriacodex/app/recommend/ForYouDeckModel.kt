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
import com.theoriacodex.data.repository.ForYouVerdictRepository
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.vision.PictureTags
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

class ForYouDeckWiring(
    val actions: TriageActions,
    val quickSave: QuickSave,
    val trails: suspend (request: TrailRequest, animatedOnly: Boolean) -> TrailSpec?,
    val relatedLoader: RelatedPostsLoading,
    val writeScope: CoroutineScope,
    val reconnections: Flow<Unit> = emptyFlow(),
    val fetchContext: CoroutineContext = Dispatchers.Default,
    val verdicts: ForYouVerdictRepository,
    val copiesOf: (Post) -> Set<PostId> = { setOf(it.id) },
    val clock: () -> Long = System::currentTimeMillis,
    val pictures: PictureTags = PictureTags.None,
)

data class ForYouEnvironment(
    val settings: AppSettings,
    val selectedSource: SourceKey?,
    val sortMode: SortMode,
    val animatedOnly: Boolean,
    val grid: Boolean,
    val kept: Set<PostId>,
    val creatorBrowsingSources: Set<SourceKey>,
)

data class ForYouLaneChoice(val laneId: String, val label: String, val cooled: Boolean = false)

enum class ForYouMixState {
    Dealing,
    NoSources,
    NoLanes,
    Failed,
}

data class ForYouDeckUiState(
    val profiles: List<RecommendationProfile> = emptyList(),
    val activeProfileId: String = "",
    val availableSources: List<SourceKey> = emptyList(),
    val selectedSource: SourceKey? = null,
    val lanes: List<ForYouLaneChoice> = emptyList(),
    val failures: List<SourceRunStatus> = emptyList(),
    val mix: ForYouMixState = ForYouMixState.Dealing,
    val failure: String? = null,
)

class ForYouLevel internal constructor(
    val id: String,
    val label: String,
    val fork: TrailRequest?,
    val query: Query?,
    val deck: TriageDeck,
    val feed: StateFlow<TriageFeedSnapshot>,
    private val source: TriageFeed,
    private val scope: CoroutineScope,
    private val onResolved: (Post) -> Unit,
) {
    internal var leftWith: Set<PostId> = emptySet()

    fun loadNextPage() = source.loadNextPage()
    fun retryPage() = source.retryPage()

    internal fun remember(post: Post) = onResolved(post)

    internal fun viewerFeed(feed: TriageFeedSnapshot, related: RelatedPostsUiState) = ViewerFeedSnapshot(
        queryHash = id,
        results = feed.posts,
        canLoadMore = feed.canLoadMore,
        loadingMore = feed.loadingMore,
        pageFailed = feed.pageFailed && !feed.loadingMore,
        relatedPosts = related,
    )

    internal fun close() {
        deck.close()
        scope.cancel()
    }
}

internal data class MixInputs(
    val profileId: String,
    val sources: List<SourceKey>,
    val source: SourceKey?,
    val sort: SortMode,
    val animatedOnly: Boolean,
)

internal val LOADING_FEED = TriageFeedSnapshot(emptyList(), canLoadMore = true, loadingMore = true)
