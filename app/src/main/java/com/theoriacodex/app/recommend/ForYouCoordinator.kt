package com.theoriacodex.app.recommend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.defaultRecommendationProfiles
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.recommendation.ForYouTagSetGenerator
import com.theoriacodex.domain.recommendation.TagAffinityStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.random.Random

class ForYouCoordinator(
    private val registry: SourceAdapterRegistry,
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    private val likesRepository: LikesRepository = InMemoryLikesRepository(),
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
) {
    private var runtimeSettings: AppSettings = AppSettings()
    private var hasExecutedFeed = false
    private var nextPageTokens: Map<SourceKey, String?> = emptyMap()
    private var queryOverridesBySource: Map<SourceKey, Query> = emptyMap()
    private var affinityStatsBySource: Map<SourceKey, TagAffinityStats> = emptyMap()

    var results by mutableStateOf<List<Post>>(emptyList())
        private set

    var statuses by mutableStateOf<List<SourceRunStatus>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var canLoadMore by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var activeProfileId by mutableStateOf(defaultRecommendationProfiles().first().profileId)
        private set

    var activeProfileLikesCount by mutableStateOf(0)
        private set

    var seedSummaryBySource by mutableStateOf<Map<SourceKey, List<String>>>(emptyMap())
        private set

    var seedId by mutableStateOf("init")
        private set

    var sortMode by mutableStateOf(SortMode.NEWEST)
        private set

    suspend fun initialize() {
        runtimeSettings = settingsRepository.observeSettings().first()
        activeProfileId = runtimeSettings.activeProfileId
    }

    fun onSettingsChanged(settings: AppSettings): Boolean {
        val previousRuntime = runtimeSettings.runtime
        val previousProfile = runtimeSettings.activeProfileId
        runtimeSettings = settings
        activeProfileId = settings.activeProfileId
        return hasExecutedFeed &&
            (previousRuntime != settings.runtime || previousProfile != settings.activeProfileId)
    }

    suspend fun refresh(shuffle: Boolean = true) {
        if (loading) return
        executeFeed(shuffle = shuffle)
    }

    suspend fun setSortMode(mode: SortMode) {
        if (sortMode == mode) return
        sortMode = mode
        refresh(shuffle = false)
    }

    fun clear() {
        results = emptyList()
        statuses = emptyList()
        loading = false
        loadingMore = false
        canLoadMore = false
        errorMessage = null
        activeProfileLikesCount = 0
        seedSummaryBySource = emptyMap()
        seedId = "empty"
        nextPageTokens = emptyMap()
        queryOverridesBySource = emptyMap()
        affinityStatsBySource = emptyMap()
    }

    fun displayTagFor(post: Post): String? {
        return associatedDisplayTag(
            post = post,
            seedTagsBySource = seedSummaryBySource,
            affinityBySource = affinityStatsBySource,
        )
    }

    suspend fun loadNextPage() {
        if (loading || loadingMore || !canLoadMore) return
        loadingMore = true
        errorMessage = null
        try {
            val pageableSources = queryOverridesBySource.keys.filterTo(mutableSetOf()) { source ->
                !nextPageTokens[source].isNullOrBlank()
            }
            if (pageableSources.isEmpty()) {
                canLoadMore = false
                return
            }

            val pageResult = runUnifiedSearch(
                sources = pageableSources,
                pageTokens = nextPageTokens.filterKeys { source -> source in pageableSources },
            )
            results = mergeResults(results, pageResult.items)
            statuses = pageResult.statuses.sortedBy { it.source.name }
            nextPageTokens = nextPageTokens.toMutableMap().apply {
                putAll(pageResult.nextPageTokens)
            }
            canLoadMore = nextPageTokens.values.any { token -> !token.isNullOrBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = error.message ?: "Could not load more recommendations"
            canLoadMore = false
        } finally {
            loadingMore = false
        }
    }

    fun buildViewerLaunchContext(
        startIndex: Int,
        scrollOffsetHint: Int,
    ): ViewerLaunchContext {
        return ViewerLaunchContext(
            queryHash = "for_you:$seedId",
            startIndex = startIndex,
            streamSource = ViewerStreamSource.FOR_YOU,
            scrollOffsetHint = scrollOffsetHint,
        )
    }

    private suspend fun executeFeed(shuffle: Boolean) {
        loading = true
        loadingMore = false
        canLoadMore = false
        errorMessage = null
        statuses = emptyList()
        nextPageTokens = emptyMap()
        try {
            val enabledSources = effectiveEnabledSources()
            val likes = likesRepository.observeLikes(activeProfileId).first()
            activeProfileLikesCount = likes.size
            affinityStatsBySource = buildAffinityBySource(
                likes = likes,
                enabledSources = enabledSources,
            )

            if (enabledSources.isEmpty()) {
                results = emptyList()
                queryOverridesBySource = emptyMap()
                seedSummaryBySource = emptyMap()
                seedId = "empty-enabled"
                affinityStatsBySource = emptyMap()
                return
            }

            val personalizedSeed = buildSeedBySource(
                likes = likes,
                enabledSources = enabledSources,
                shuffle = shuffle,
            )
            val initialSeed = if (personalizedSeed.isNotEmpty()) {
                personalizedSeed
            } else {
                buildTrendingSeed(enabledSources)
            }

            if (initialSeed.isEmpty()) {
                results = emptyList()
                queryOverridesBySource = emptyMap()
                seedSummaryBySource = emptyMap()
                seedId = "empty-seed"
                return
            }

            val initialResult = runFeedSeed(initialSeed)
            if (initialResult.items.isNotEmpty() || activeProfileLikesCount == 0) {
                applyFeedResult(seed = initialSeed, result = initialResult)
                return
            }

            val trendingSeed = buildTrendingSeed(enabledSources)
            if (trendingSeed.isNotEmpty() && trendingSeed != initialSeed) {
                val trendingResult = runFeedSeed(trendingSeed)
                applyFeedResult(seed = trendingSeed, result = trendingResult)
            } else {
                applyFeedResult(seed = initialSeed, result = initialResult)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            results = emptyList()
            queryOverridesBySource = emptyMap()
            seedSummaryBySource = emptyMap()
            affinityStatsBySource = emptyMap()
            canLoadMore = false
            errorMessage = error.message ?: "Could not load recommendations"
        } finally {
            hasExecutedFeed = true
            loading = false
        }
    }

    private suspend fun runFeedSeed(
        seed: Map<SourceKey, List<String>>,
    ): UnifiedSearchResult {
        queryOverridesBySource = seed.mapValues { (source, includeTags) ->
            sourceQuery(source = source, includeTags = includeTags)
        }
        return runUnifiedSearch(
            sources = seed.keys,
            pageTokens = emptyMap(),
        )
    }

    private fun applyFeedResult(
        seed: Map<SourceKey, List<String>>,
        result: UnifiedSearchResult,
    ) {
        results = result.items
        statuses = result.statuses.sortedBy { it.source.name }
        nextPageTokens = result.nextPageTokens
        canLoadMore = nextPageTokens.values.any { token -> !token.isNullOrBlank() }
        seedSummaryBySource = seed
        seedId = buildSeedId(seed)
    }

    private suspend fun runUnifiedSearch(
        sources: Set<SourceKey>,
        pageTokens: Map<SourceKey, String?>,
    ): UnifiedSearchResult {
        return registry.unifiedOrchestrator().search(
            query = baseQuery(),
            enabledSources = sources,
            pageTokens = pageTokens,
            weights = effectiveWeights(sources),
            queryOverridesBySource = queryOverridesBySource.filterKeys { source -> source in sources },
        )
    }

    private suspend fun buildSeedBySource(
        likes: List<LikedPost>,
        enabledSources: Set<SourceKey>,
        shuffle: Boolean,
    ): Map<SourceKey, List<String>> {
        val random = if (shuffle) {
            Random(System.currentTimeMillis())
        } else {
            Random(0L)
        }

        return enabledSources
            .sortedBy { it.name }
            .mapNotNull { source ->
                val documents = likes
                    .asSequence()
                    .filter { liked -> liked.postId.source == source }
                    .map { liked -> liked.tags }
                    .toList()
                if (documents.isEmpty()) {
                    return@mapNotNull null
                }
                val fallbackTags = fallbackTagsForSource(source)
                val includeTags = ForYouTagSetGenerator.generate(
                    source = source,
                    likedDocuments = documents,
                    fallbackCandidates = fallbackTags,
                    random = random,
                )
                includeTags.takeIf { it.isNotEmpty() }?.let { tags -> source to tags }
            }
            .toMap()
    }

    private suspend fun buildTrendingSeed(
        enabledSources: Set<SourceKey>,
    ): Map<SourceKey, List<String>> {
        return enabledSources
            .sortedBy { it.name }
            .mapNotNull { source ->
                val fallbackTags = fallbackTagsForSource(source)
                val includeTags = ForYouTagSetGenerator.generate(
                    source = source,
                    likedDocuments = emptyList(),
                    fallbackCandidates = fallbackTags,
                    random = Random(source.name.hashCode()),
                )
                includeTags.takeIf { it.isNotEmpty() }?.let { tags -> source to tags }
            }
            .toMap()
    }

    private suspend fun fallbackTagsForSource(source: SourceKey): List<String> {
        val cached = tagSuggestionStore.get(source = source, limit = TRENDING_FALLBACK_LIMIT)
        if (cached.isNotEmpty()) {
            return cached.map { suggestion -> suggestion.text }
        }

        val fetched = runCatching {
            registry.adapterFor(source)?.trendingTags(limit = TRENDING_FALLBACK_LIMIT).orEmpty()
        }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            tagSuggestionStore.put(source, fetched)
        }
        return fetched.map { suggestion -> suggestion.text }
    }

    private fun sourceQuery(
        source: SourceKey,
        includeTags: List<String>,
    ): Query {
        return Query(
            mode = QueryMode.Source(source),
            includeTags = includeTags,
            excludeTags = emptyList(),
            sort = sortMode,
            dateRange = null,
            minScore = null,
        )
    }

    private fun effectiveEnabledSources(): Set<SourceKey> {
        return runtimeSettings.runtime.enabledSources.intersect(registry.availableSources())
    }

    private fun effectiveWeights(enabledSources: Set<SourceKey>): Map<SourceKey, Double> {
        if (enabledSources.isEmpty()) return emptyMap()
        val raw = enabledSources.associateWith { source ->
            runtimeSettings.runtime.sourceWeights[source] ?: 1.0
        }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: enabledSources.size.toDouble()
        return raw.mapValues { (_, weight) -> weight / total }
    }

    private fun baseQuery(): Query {
        return Query(
            mode = QueryMode.Unified,
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = sortMode,
            dateRange = null,
            minScore = null,
        )
    }

    private fun mergeResults(
        current: List<Post>,
        next: List<Post>,
    ): List<Post> {
        if (next.isEmpty()) return current
        if (current.isEmpty()) return next

        val seen = current
            .mapTo(mutableSetOf()) { post -> "${post.id.source.name}:${post.id.sourcePostId}" }
        val merged = current.toMutableList()
        next.forEach { post ->
            val key = "${post.id.source.name}:${post.id.sourcePostId}"
            if (seen.add(key)) {
                merged += post
            }
        }
        return merged
    }

    private fun buildSeedId(seed: Map<SourceKey, List<String>>): String {
        if (seed.isEmpty()) return "none"
        return seed.entries
            .sortedBy { it.key.name }
            .joinToString(separator = "|") { (source, tags) ->
                "${source.name}:${tags.joinToString(separator = "+")}"
            }
    }

    private fun buildAffinityBySource(
        likes: List<LikedPost>,
        enabledSources: Set<SourceKey>,
    ): Map<SourceKey, TagAffinityStats> {
        val documentsBySource = enabledSources
            .associateWith { source ->
                likes
                    .asSequence()
                    .filter { liked -> liked.postId.source == source }
                    .map { liked -> liked.tags }
                    .toList()
            }
            .filterValues { documents -> documents.isNotEmpty() }
        return buildSourceTagAffinity(documentsBySource = documentsBySource)
    }
}

private const val TRENDING_FALLBACK_LIMIT = 20
