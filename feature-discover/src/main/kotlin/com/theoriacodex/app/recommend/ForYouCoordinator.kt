package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.feed.nextPage
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.app.source.PostResolutionFlights
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The For You deck's engine: builds a Mix's lanes and loads their pages. Every page, whichever lane or source it is
 * for, teaches the file mirror index and records its posts as available, as Search's pages do.
 */
class ForYouCoordinator(
    private val registry: SourceAdapterRegistry,
    likesRepository: LikesRepository,
    private val statisticsRepository: StatisticsRepository,
    /** A profile's tag affinity, which ranks each source's interests; see [SharedTagAffinity.forProfile]. */
    tagAffinity: suspend (profileId: String) -> TagAffinityInputs,
    tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Shared with the Viewer, so a card and the Viewer asking for one post at once fetch it once. */
    private val postResolutions: PostResolutionFlights = PostResolutionFlights(),
    private val fileMirrors: FileMirrorIndex = FileMirrorIndex(),
    random: () -> Random? = { Random.Default },
) : ForYouRouteEngine {
    private val lanes = ForYouLanesLoader(registry, tagSuggestionStore, likesRepository, tagAffinity, computationDispatcher, random)

    override fun availableSources(settings: AppSettings): List<SourceKey> =
        settings.runtime.enabledSources.intersect(registry.availableSources()).inPresentationOrder()

    override suspend fun lanes(request: ForYouFeedRequest): List<ForYouLane> = lanes.load(request)

    override suspend fun loadPage(continuation: FeedCursor, sources: Set<SourceKey>): UnifiedSearchResult =
        registry.unifiedOrchestrator().nextPage(continuation, sources).also { page ->
            fileMirrors.learn(page.items)
            page.items.forEach { post ->
                runCatchingPreservingCancellation { statisticsRepository.recordPostAvailable(post) }
            }
        }

    override suspend fun resolvePost(postId: PostId): Post? {
        val adapter = registry.adapterFor(postId.source) ?: return null
        return postResolutions.resolve(postId) { adapter.resolvePost(postId) }
    }
}
