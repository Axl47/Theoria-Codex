package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceWeightNormalization
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.recommendation.ForYouTagSetGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Executes immutable feed inputs; only the route may accept a result or record its history. */
class ForYouCoordinator(
    private val registry: SourceAdapterRegistry,
    private val settingsRepository: SettingsRepository,
    private val likesRepository: LikesRepository,
    private val recentsRepository: RecentsRepository,
    private val statisticsRepository: StatisticsRepository,
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    private val seedSource: () -> Long = System::currentTimeMillis,
    private val savedSearches: com.theoriacodex.data.repository.SavedSearchRepository =
        com.theoriacodex.data.repository.InMemorySavedSearchRepository(),
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ForYouRouteEngine {
    private val sections = ForYouSectionsLoader(registry, tagSuggestionStore, likesRepository, savedSearches, ::runFeedSeed)

    override suspend fun loadSections(request: ForYouFeedRequest): List<ForYouSection> = sections.load(request)
    override suspend fun pinSection(section: ForYouSection, pinned: Boolean): String? = sections.pin(section, pinned)

    override fun availableSources(settings: AppSettings): List<SourceKey> =
        settings.runtime.enabledSources.intersect(registry.availableSources()).inPresentationOrder()

    override suspend fun execute(request: ForYouFeedRequest): ForYouFeedResult {
        val historical = request.historicalSeed
        if (historical != null) {
            require(historical.isNotEmpty()) { "Saved FYP search has no sources" }
            require(request.availableSources.containsAll(historical.keys)) {
                "Saved FYP sources are currently unavailable"
            }
            return runFeedSeed(request, historical, request.likesCount)
        }
        val enabledSources = request.availableSources.filterTo(linkedSetOf()) {
            request.selectedSource == null || it == request.selectedSource
        }
        val likes = likesRepository.observeLikes(request.settings.activeProfileId).first()
        if (enabledSources.isEmpty()) return ForYouFeedResult.empty("empty-enabled", likes.size)
        val blocked = blacklistedSeedKeysBySource(request.settings)
        val personalized = buildSeedBySource(likes, enabledSources, request.shuffle, blocked)
        val initialSeed = personalized.ifEmpty { buildTrendingSeed(enabledSources, blocked) }
        if (initialSeed.isEmpty()) return ForYouFeedResult.empty("empty-seed", likes.size)
        val initial = runFeedSeed(request, initialSeed, likes.size)
        if (initial.page.items.isNotEmpty() || likes.isEmpty() ||
            initial.page.statuses.none { it.state == SourceRunState.SUCCESS }
        ) return initial
        val trending = buildTrendingSeed(enabledSources, blocked)
        return if (trending.isNotEmpty() && trending != initialSeed) {
            runFeedSeed(request, trending, likes.size)
        } else initial
    }

    override suspend fun loadPage(continuation: ForYouContinuation, sources: Set<SourceKey>): UnifiedSearchResult =
        registry.unifiedOrchestrator().search(
            query = continuation.query,
            enabledSources = sources,
            pageTokens = continuation.pageTokens.filterKeys { it in sources },
            weights = SourceWeightNormalization.normalize(sources, continuation.weights),
            queryOverridesBySource = continuation.queriesBySource.filterKeys { it in sources },
        )

    override suspend fun updateBlacklist(
        profileId: String,
        entries: List<ForYouBlacklistEntry>,
        hidden: Boolean,
    ): ForYouBlacklistChange {
        val changed = entries.filter { entry ->
            if (hidden) settingsRepository.addForYouBlacklistEntry(profileId, entry.source, entry.tags)
            else settingsRepository.removeForYouBlacklistEntry(profileId, entry.source, entry.tags)
        }
        return ForYouBlacklistChange(settingsRepository.observeSettings().first(), changed)
    }

    override suspend fun recordAccepted(result: ForYouFeedResult) {
        if (result.seed.isEmpty() || result.page.statuses.none { it.state == SourceRunState.SUCCESS }) return
        runCatchingPreservingCancellation {
            recentsRepository.recordSearch(
                query = requireNotNull(result.continuation).query.withIncludeTermsAsRequired(
                    result.seed.values.flatten().distinct().map(::SearchTerm),
                ),
                queryHash = "for_you:${result.seedId}",
                kind = RecentSearchKind.FYP,
                sources = result.seed.keys.toList(),
                sourceTags = result.seed,
            )
        }
        runCatchingPreservingCancellation { statisticsRepository.recordForYouSearch() }
    }

    override suspend fun resolvePost(postId: PostId): Post? =
        registry.adapterFor(postId.source)?.resolvePost(postId)

    private suspend fun runFeedSeed(
        request: ForYouFeedRequest,
        seed: Map<SourceKey, List<String>>,
        likesCount: Int,
    ): ForYouFeedResult {
        val continuation = ForYouContinuation(
            query = forYouBaseQuery(request.sortMode),
            queriesBySource = seed.mapValues { (source, tags) -> forYouSourceQuery(source, tags, request.sortMode) },
            weights = SourceWeightNormalization.normalize(seed.keys, request.settings.runtime.sourceWeights),
            pageTokens = seed.keys.associateWith { null },
        )
        val page = loadPage(continuation, seed.keys)
        return ForYouFeedResult(seed, buildForYouSeedId(seed), likesCount, page, continuation.accept(page, seed.keys))
    }

    private suspend fun buildSeedBySource(
        likes: List<LikedPost>,
        enabledSources: Set<SourceKey>,
        shuffle: Boolean,
        blacklistedSeedKeys: Map<SourceKey, Set<String>>,
    ): Map<SourceKey, List<String>> {
        val random = if (shuffle) {
            Random(seedSource())
        } else {
            Random(0L)
        }

        val preparedBySource = withContext(computationDispatcher) {
            likes.asSequence()
                .filter { liked -> liked.postId.source in enabledSources }
                .groupBy { liked -> liked.postId.source }
                .mapValues { (source, sourceLikes) ->
                    ForYouTagSetGenerator.prepare(source, sourceLikes.map(LikedPost::tags))
                }
        }
        val fallbackBySource = preparedBySource.filterValues { it.needsFallback }.keys
            .mapConcurrent { source -> source to fallbackTagsForSource(source) }.toMap()
        return enabledSources
            .inPresentationOrder()
            .mapNotNull { source ->
                val prepared = preparedBySource[source] ?: return@mapNotNull null
                val fallbackTags = fallbackBySource[source].orEmpty()
                val includeTags = selectAllowedSeed(
                    prepared = prepared,
                    fallbackCandidates = fallbackTags,
                    random = random,
                    blockedKeys = blacklistedSeedKeys[source].orEmpty(),
                )
                includeTags.takeIf { it.isNotEmpty() }?.let { tags -> source to tags }
            }
            .toMap()
    }

    private suspend fun buildTrendingSeed(
        enabledSources: Set<SourceKey>,
        blacklistedSeedKeys: Map<SourceKey, Set<String>>,
    ): Map<SourceKey, List<String>> {
        return enabledSources
            .inPresentationOrder()
            .mapConcurrent { source ->
                val fallbackTags = fallbackTagsForSource(source)
                val includeTags = selectAllowedSeed(
                    prepared = ForYouTagSetGenerator.prepare(source, emptyList()),
                    fallbackCandidates = fallbackTags,
                    random = Random(source.name.hashCode()),
                    blockedKeys = blacklistedSeedKeys[source].orEmpty(),
                )
                includeTags.takeIf { it.isNotEmpty() }?.let { tags -> source to tags }
            }
            .filterNotNull().toMap()
    }

    private suspend fun selectAllowedSeed(
        prepared: ForYouTagSetGenerator.Prepared,
        fallbackCandidates: List<String>,
        random: Random,
        blockedKeys: Set<String>,
    ): List<String> = withContext(computationDispatcher) {
        repeat(FOR_YOU_SEED_ATTEMPTS) {
            val includeTags = prepared.sample(
                fallbackCandidates = fallbackCandidates,
                random = random,
            )
            if (includeTags.isEmpty()) {
                return@withContext emptyList()
            }
            if (seedKey(includeTags) !in blockedKeys) {
                return@withContext includeTags
            }
        }
        emptyList()
    }

    private suspend fun fallbackTagsForSource(source: SourceKey): List<String> {
        val cached = tagSuggestionStore
            .getTrending(source = source, limit = TRENDING_FALLBACK_LIMIT)
            .filter(TagSuggestion::isRecommendationTagSuggestion)
        if (cached.isNotEmpty()) {
            return cached.map { suggestion -> suggestion.text }
        }

        val fetched = runCatchingPreservingCancellation {
            withTimeoutOrNull(4_000L) {
                registry.adapterFor(source)?.trendingTags(limit = TRENDING_FALLBACK_LIMIT).orEmpty()
            }.orEmpty()
        }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            withContext(computationDispatcher) { tagSuggestionStore.replaceTrending(source, fetched) }
        }
        val fallback = fetched.ifEmpty {
            tagSuggestionStore.get(source = source, limit = TRENDING_FALLBACK_LIMIT)
        }
        return fallback
            .filter(TagSuggestion::isRecommendationTagSuggestion)
            .map { suggestion -> suggestion.text }
    }

    private fun blacklistedSeedKeysBySource(settings: AppSettings): Map<SourceKey, Set<String>> {
        return settings
            .forYouBlacklistByProfile[settings.activeProfileId]
            .orEmpty()
            .groupBy { entry -> entry.source }
            .mapValues { (_, entries) ->
                entries
                    .asSequence()
                    .map { entry -> seedKey(entry.tags) }
                    .filter { key -> key.isNotBlank() }
                    .toSet()
            }
    }

    private fun seedKey(tags: List<String>): String {
        return tags
            .asSequence()
            .map { tag -> tag.trim().lowercase() }
            .filter { tag -> tag.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(separator = "+")
    }
}

private const val TRENDING_FALLBACK_LIMIT = 20
private const val FOR_YOU_SEED_ATTEMPTS = 16
