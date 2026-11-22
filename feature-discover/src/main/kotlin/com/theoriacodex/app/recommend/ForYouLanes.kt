package com.theoriacodex.app.recommend

import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.search.TagCountCoordinator
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.recommendation.CrossSourceTraining
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds a Mix's lanes: the profile's interests on each source, ranked by [tagAffinity] as Trails rank chips, taken in
 * turn across sources so every source gets a lane, or the sources' trending tags when its history gives none. Lanes the
 * profile's hidden tags hide are left out.
 * [random] varies which interests lead between Mixes; null keeps them in weight order.
 */
internal class ForYouLanesLoader(
    private val registry: SourceAdapterRegistry,
    private val store: TagSuggestionStore,
    private val likes: LikesRepository,
    private val tagAffinity: Flow<TagAffinityInputs>,
    private val computation: CoroutineDispatcher,
    private val random: () -> Random?,
) {
    private val counts = TagCountCoordinator(registry, store, { _, tag -> tag })

    suspend fun load(request: ForYouFeedRequest): List<ForYouLane> = withContext(computation) {
        val sources = request.availableSources.filter { request.selectedSource == null || it == request.selectedSource }
            .inPresentationOrder()
        val profile = request.settings.activeProfileId
        val blocked = request.settings.forYouBlacklistByProfile[profile].orEmpty()
        val interests = interestSeeds(sources, likes.observeLikes(profile).first(), blocked)
        val seeds = interests.ifEmpty { trendingSeeds(sources, blocked) }
        seeds.inTurnBySource().take(LANE_LIMIT).map { (source, tags) -> lane(source, tags, request) }
    }

    /** Each source's interests, strongest first, ending with a guess to explore. */
    private suspend fun interestSeeds(sources: List<SourceKey>, liked: List<LikedPost>, blocked: List<ForYouBlacklistEntry>): List<LaneSeed> {
        val affinity = tagAffinity.first()
        if (liked.isEmpty() && affinity.keptBySource.isEmpty() && affinity.explicit.isEmpty()) return emptyList()
        val training = crossSourceTraining(liked, store)
        return sources.mapConcurrent { source ->
            availableInterests(source, training, affinity).map { LaneSeed(source, it.tags) }
        }.flatten().filterNot { blocked.hidesSeed(it.source, it.tags) }
    }

    /**
     * [source]'s interests, picked again without any tag the source reports no posts for, since its lane would stay
     * empty. Counts are asked only for the picked tags; after [AVAILABILITY_ROUNDS] picks, interests with such tags drop.
     */
    private suspend fun availableInterests(source: SourceKey, training: CrossSourceTraining, affinity: TagAffinityInputs): List<ForYouInterest> {
        val documents = training.documentsFor(source)
        val translate = { tag: String, into: SourceKey -> training.translate(tag, source, into) }
        var unavailable = emptySet<String>()
        for (round in 1..AVAILABILITY_ROUNDS) {
            val interests = ForYouInterests.generate(source, documents, affinity, translate, unavailable, random())
            val providerCounts = counts.fetch(source, interests.flatMap { it.tags })
            val missing = interests.flatMap { it.tags }.filterTo(mutableSetOf()) { providerCounts[it] == 0 }
            if (missing.isEmpty() || round == AVAILABILITY_ROUNDS) return interests.filter { it.tags.none(missing::contains) }
            unavailable += missing
        }
        return emptyList()
    }

    /** One lane per trending tag, for a profile whose history gives no interests. */
    private suspend fun trendingSeeds(sources: List<SourceKey>, blocked: List<ForYouBlacklistEntry>): List<LaneSeed> =
        sources.mapConcurrent { source ->
            trendingTags(source).map { LaneSeed(source, listOf(it)) }
                .filterNot { blocked.hidesSeed(it.source, it.tags) }.take(TRENDING_LANES_PER_SOURCE)
        }.flatten()

    private suspend fun trendingTags(source: SourceKey): List<String> {
        val cached = store.getTrending(source = source, limit = TRENDING_LIMIT).filter(TagSuggestion::isRecommendationTagSuggestion)
        if (cached.isNotEmpty()) return cached.map { it.text }
        val fetched = runCatchingPreservingCancellation {
            withTimeoutOrNull(TRENDING_TIMEOUT_MS) { registry.adapterFor(source)?.trendingTags(limit = TRENDING_LIMIT).orEmpty() }.orEmpty()
        }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) store.replaceTrending(source, fetched)
        return fetched.ifEmpty { store.get(source = source, limit = TRENDING_LIMIT) }
            .filter(TagSuggestion::isRecommendationTagSuggestion).map { it.text }
    }

    private fun lane(source: SourceKey, tags: List<String>, request: ForYouFeedRequest): ForYouLane {
        // The source filters by media where it can; the deck's owner filters again for sources that cannot.
        val media = if (request.animatedOnly) MediaFilter.ANIMATED else MediaFilter.ANY
        return ForYouLane(
            source, tags,
            FeedCursor(
                query = forYouBaseQuery(request.sortMode),
                queriesBySource = mapOf(source to forYouSourceQuery(source, tags, request.sortMode).copy(media = media)),
                weights = mapOf(source to 1.0),
                pageTokens = mapOf(source to null),
            ),
        )
    }
}

private data class LaneSeed(val source: SourceKey, val tags: List<String>)

/** Every source's first seed, then every source's second, and so on. */
private fun List<LaneSeed>.inTurnBySource(): List<LaneSeed> {
    val groups = groupBy { it.source }.values.toList()
    return (0 until (groups.maxOfOrNull { it.size } ?: 0)).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }
}

private fun forYouBaseQuery(sort: SortMode): Query = Query(
    mode = QueryMode.Unified, includeTags = emptyList(), excludeTags = emptyList(),
    sort = sort, dateRange = null, minScore = null,
)

/** The most lanes a Mix runs: each opens with its own request, and each gets a chip. */
private const val LANE_LIMIT = 6

/** How many times a source's interests are picked again around tags it has no posts for. */
private const val AVAILABILITY_ROUNDS = 3
private const val TRENDING_LANES_PER_SOURCE = 3
private const val TRENDING_LIMIT = 20
private const val TRENDING_TIMEOUT_MS = 4_000L
