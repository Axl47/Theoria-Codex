package com.theoriacodex.app.recommend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.app.source.inPresentationOrder
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
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.SourceWeightNormalization
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.recommendation.ForYouTagSetGenerator
import com.theoriacodex.domain.recommendation.TagAffinityStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class ForYouCoordinator(
    private val registry: SourceAdapterRegistry,
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    private val likesRepository: LikesRepository = InMemoryLikesRepository(),
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    private val seedSource: () -> Long = System::currentTimeMillis,
) {
    private val initializationMutex = Mutex()
    private val feedRequestLock = Any()
    private var runtimeSettings: AppSettings = AppSettings()
    private var hasExecutedFeed = false
    @Volatile
    private var initialized = false
    private var availableSourcesSnapshot = registry.availableSources()
    private var nextFeedGeneration = 0L
    private var activeFeedRequest: FeedRequest? = null
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

    var selectedSource by mutableStateOf<SourceKey?>(null)
        private set

    val availableSourceSelections: List<SourceKey>
        get() = allEnabledSources().inPresentationOrder()

    var seedSummaryBySource by mutableStateOf<Map<SourceKey, List<String>>>(emptyMap())
        private set

    var seedId by mutableStateOf("init")
        private set

    var sortMode by mutableStateOf(SortMode.NEWEST)
        private set

    val isInitialized: Boolean
        get() = initialized

    /** Restores compact route controls without starting provider work. */
    internal fun restoreRouteInputs(
        source: SourceKey?,
        sort: SortMode?,
    ) {
        selectedSource = source?.takeIf { candidate -> candidate in allEnabledSources() }
        if (sort != null) sortMode = sort
    }

    suspend fun initialize() {
        if (initialized) return
        initializationMutex.withLock {
            if (initialized) return@withLock
            runtimeSettings = settingsRepository.observeSettings().first()
            activeProfileId = runtimeSettings.activeProfileId
            availableSourcesSnapshot = registry.availableSources()
            initialized = true
        }
    }

    fun onSettingsChanged(settings: AppSettings): Boolean {
        val previousRuntime = runtimeSettings.runtime
        val previousProfile = runtimeSettings.activeProfileId
        val previousBlacklist = runtimeSettings.forYouBlacklistByProfile
        val previousSelectedSource = selectedSource
        runtimeSettings = settings
        activeProfileId = settings.activeProfileId
        if (selectedSource !in allEnabledSources()) {
            selectedSource = null
        }
        val feedInputsChanged = previousRuntime != settings.runtime ||
            previousProfile != settings.activeProfileId ||
            previousBlacklist != settings.forYouBlacklistByProfile ||
            previousSelectedSource != selectedSource
        val cancelledActiveRequest = if (feedInputsChanged) invalidateActiveRequest() else false
        return cancelledActiveRequest || (hasExecutedFeed && feedInputsChanged)
    }

    fun onAvailableSourcesChanged(): Boolean {
        val currentSources = registry.availableSources()
        val sourcesChanged = currentSources != availableSourcesSnapshot
        availableSourcesSnapshot = currentSources
        val previousSelectedSource = selectedSource
        if (selectedSource !in allEnabledSources()) {
            selectedSource = null
        }
        val cancelledActiveRequest = if (sourcesChanged) invalidateActiveRequest() else false
        return cancelledActiveRequest ||
            (hasExecutedFeed && (sourcesChanged || previousSelectedSource != selectedSource))
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

    suspend fun setSourceSelection(source: SourceKey?) {
        if (loading || loadingMore) return
        val normalizedSource = source?.takeIf { candidate -> candidate in allEnabledSources() }
        if (selectedSource == normalizedSource) return
        selectedSource = normalizedSource
        executeFeed(shuffle = false)
    }

    suspend fun blacklistCurrentSeedAndRefresh(): Int {
        val currentSeed = seedSummaryBySource
        if (currentSeed.isEmpty()) return 0

        var additions = 0
        currentSeed.forEach { (source, tags) ->
            val added = settingsRepository.addForYouBlacklistEntry(
                profileId = activeProfileId,
                source = source,
                tags = tags,
            )
            if (added) {
                additions += 1
            }
        }

        runtimeSettings = settingsRepository.observeSettings().first()
        refresh(shuffle = true)
        return additions
    }

    fun clear() {
        invalidateActiveRequest()
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
        val request = beginRequest(FeedRequestKind.PAGE)
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
            ensureCurrent(request)
            results = mergeResults(results, pageResult.items)
            statuses = pageResult.statuses.sortedBy { it.source.name }
            nextPageTokens = nextPageTokens.toMutableMap().apply {
                putAll(pageResult.nextPageTokens)
            }
            canLoadMore = nextPageTokens.values.any { token -> !token.isNullOrBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            publishIfCurrent(request) {
                errorMessage = error.message ?: "Could not load more recommendations"
                canLoadMore = false
            }
        } finally {
            finishRequest(request)
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

    suspend fun resolvePostForFeed(postId: PostId): Post? {
        val adapter = registry.adapterFor(postId.source) ?: return null
        val resolved = adapter.resolvePost(postId) ?: return null
        rememberResolvedPost(resolved)
        return resolved
    }

    fun rememberResolvedPost(post: Post) {
        val index = results.indexOfFirst { current -> current.id == post.id }
        if (index < 0) return
        results = results.toMutableList().apply {
            this[index] = post
        }
    }

    private suspend fun executeFeed(shuffle: Boolean) {
        val request = beginRequest(FeedRequestKind.ROOT)
        try {
            val enabledSources = effectiveEnabledSources()
            val blacklistedSeedKeys = blacklistedSeedKeysBySource()
            val likes = likesRepository.observeLikes(activeProfileId).first()
            ensureCurrent(request)
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
                blacklistedSeedKeys = blacklistedSeedKeys,
            )
            ensureCurrent(request)
            val initialSeed = if (personalizedSeed.isNotEmpty()) {
                personalizedSeed
            } else {
                buildTrendingSeed(
                    enabledSources = enabledSources,
                    blacklistedSeedKeys = blacklistedSeedKeys,
                )
            }
            ensureCurrent(request)

            if (initialSeed.isEmpty()) {
                results = emptyList()
                queryOverridesBySource = emptyMap()
                seedSummaryBySource = emptyMap()
                seedId = "empty-seed"
                return
            }

            val initialResult = runFeedSeed(request, initialSeed)
            ensureCurrent(request)
            if (initialResult.items.isNotEmpty() || activeProfileLikesCount == 0) {
                applyFeedResult(seed = initialSeed, result = initialResult)
                return
            }

            val trendingSeed = buildTrendingSeed(
                enabledSources = enabledSources,
                blacklistedSeedKeys = blacklistedSeedKeys,
            )
            ensureCurrent(request)
            if (trendingSeed.isNotEmpty() && trendingSeed != initialSeed) {
                val trendingResult = runFeedSeed(request, trendingSeed)
                ensureCurrent(request)
                applyFeedResult(seed = trendingSeed, result = trendingResult)
            } else {
                applyFeedResult(seed = initialSeed, result = initialResult)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            publishIfCurrent(request) {
                results = emptyList()
                queryOverridesBySource = emptyMap()
                seedSummaryBySource = emptyMap()
                affinityStatsBySource = emptyMap()
                canLoadMore = false
                errorMessage = error.message ?: "Could not load recommendations"
            }
        } finally {
            if (isCurrent(request)) hasExecutedFeed = true
            finishRequest(request)
        }
    }

    private suspend fun runFeedSeed(
        request: FeedRequest,
        seed: Map<SourceKey, List<String>>,
    ): UnifiedSearchResult {
        ensureCurrent(request)
        queryOverridesBySource = seed.mapValues { (source, includeTags) ->
            sourceQuery(source = source, includeTags = includeTags)
        }
        return runUnifiedSearch(
            sources = seed.keys,
            pageTokens = emptyMap(),
        )
    }

    private suspend fun beginRequest(kind: FeedRequestKind): FeedRequest {
        val ownerJob = currentCoroutineContext()[Job]
        val (request, previous) = synchronized(feedRequestLock) {
            val request = FeedRequest(
                generation = ++nextFeedGeneration,
                kind = kind,
                ownerJob = ownerJob,
            )
            val previous = activeFeedRequest
            activeFeedRequest = request
            when (kind) {
                FeedRequestKind.ROOT -> {
                    loading = true
                    loadingMore = false
                    canLoadMore = false
                    statuses = emptyList()
                    nextPageTokens = emptyMap()
                }
                FeedRequestKind.PAGE -> loadingMore = true
            }
            errorMessage = null
            request to previous
        }
        previous?.ownerJob?.takeIf { it !== request.ownerJob }?.cancel(
            CancellationException("For You request superseded by generation ${request.generation}")
        )
        return request
    }

    private fun invalidateActiveRequest(): Boolean {
        val request = synchronized(feedRequestLock) {
            nextFeedGeneration += 1L
            activeFeedRequest.also {
                activeFeedRequest = null
                loading = false
                loadingMore = false
            }
        }
        request?.ownerJob?.cancel(CancellationException("For You capabilities changed"))
        return request != null
    }

    private suspend fun ensureCurrent(request: FeedRequest) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(request)) {
            throw CancellationException("Stale For You generation ${request.generation}")
        }
    }

    private fun isCurrent(request: FeedRequest): Boolean {
        return synchronized(feedRequestLock) { activeFeedRequest == request }
    }

    private inline fun publishIfCurrent(request: FeedRequest, update: () -> Unit) {
        if (isCurrent(request)) update()
    }

    private fun finishRequest(request: FeedRequest) {
        synchronized(feedRequestLock) {
            if (activeFeedRequest != request) return
            activeFeedRequest = null
            when (request.kind) {
                FeedRequestKind.ROOT -> loading = false
                FeedRequestKind.PAGE -> loadingMore = false
            }
        }
    }

    private enum class FeedRequestKind { ROOT, PAGE }

    private data class FeedRequest(
        val generation: Long,
        val kind: FeedRequestKind,
        val ownerJob: Job?,
    )

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
        blacklistedSeedKeys: Map<SourceKey, Set<String>>,
    ): Map<SourceKey, List<String>> {
        val random = if (shuffle) {
            Random(seedSource())
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
                val includeTags = selectAllowedSeed(
                    source = source,
                    likedDocuments = documents,
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
            .sortedBy { it.name }
            .mapNotNull { source ->
                val fallbackTags = fallbackTagsForSource(source)
                val includeTags = selectAllowedSeed(
                    source = source,
                    likedDocuments = emptyList(),
                    fallbackCandidates = fallbackTags,
                    random = Random(source.name.hashCode()),
                    blockedKeys = blacklistedSeedKeys[source].orEmpty(),
                )
                includeTags.takeIf { it.isNotEmpty() }?.let { tags -> source to tags }
            }
            .toMap()
    }

    private fun selectAllowedSeed(
        source: SourceKey,
        likedDocuments: List<List<String>>,
        fallbackCandidates: List<String>,
        random: Random,
        blockedKeys: Set<String>,
    ): List<String> {
        repeat(FOR_YOU_SEED_ATTEMPTS) {
            val includeTags = ForYouTagSetGenerator.generate(
                source = source,
                likedDocuments = likedDocuments,
                fallbackCandidates = fallbackCandidates,
                random = random,
            )
            if (includeTags.isEmpty()) {
                return emptyList()
            }
            if (seedKey(includeTags) !in blockedKeys) {
                return includeTags
            }
        }
        return emptyList()
    }

    private suspend fun fallbackTagsForSource(source: SourceKey): List<String> {
        val cached = tagSuggestionStore
            .get(source = source, limit = TRENDING_FALLBACK_LIMIT)
            .filter(TagSuggestion::isRecommendationTagSuggestion)
        if (cached.isNotEmpty()) {
            return cached.map { suggestion -> suggestion.text }
        }

        val fetched = runCatchingPreservingCancellation {
            registry.adapterFor(source)?.trendingTags(limit = TRENDING_FALLBACK_LIMIT).orEmpty()
        }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            tagSuggestionStore.put(source, fetched)
        }
        return fetched
            .filter(TagSuggestion::isRecommendationTagSuggestion)
            .map { suggestion -> suggestion.text }
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
        val enabledSources = allEnabledSources()
        return selectedSource?.let { source ->
            enabledSources.filterTo(mutableSetOf()) { candidate -> candidate == source }
        } ?: enabledSources
    }

    private fun allEnabledSources(): Set<SourceKey> {
        return runtimeSettings.runtime.enabledSources.intersect(registry.availableSources())
    }

    private fun effectiveWeights(enabledSources: Set<SourceKey>): Map<SourceKey, Double> {
        return SourceWeightNormalization.normalize(
            sources = enabledSources,
            weightsBySource = runtimeSettings.runtime.sourceWeights,
        )
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

    private fun blacklistedSeedKeysBySource(): Map<SourceKey, Set<String>> {
        return runtimeSettings
            .forYouBlacklistByProfile[activeProfileId]
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
