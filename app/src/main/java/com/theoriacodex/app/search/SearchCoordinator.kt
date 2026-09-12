package com.theoriacodex.app.search

import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.app.recommend.recommendationTaxonomyFor
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.modeKey
import com.theoriacodex.app.search.state.queryMode
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.SourceWeightNormalization
import com.theoriacodex.domain.query.QueryHash
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import com.theoriacodex.domain.tags.normalizeGelbooruToken
import com.theoriacodex.domain.tags.normalizeMatchToken
import com.theoriacodex.domain.tags.sourceTagKey
import com.theoriacodex.domain.tags.sourceTagsMatch
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application execution service for Search.
 *
 * It owns provider access, durable writes, and bounded service caches. It never owns route jobs,
 * route continuation, queries, results, statuses, loading flags, or an observable UI snapshot.
 * Every provider call returns an immutable value that SearchViewModel admits by request identity.
 */
@Suppress("LargeClass") // F12 removes route ownership; F15 owns remaining service-size ratchets.
class SearchCoordinator(
    private val registry: SourceAdapterRegistry,
    private val queryRepository: QueryRepository,
    private val settingsRepository: SettingsRepository,
    private val uiRestoreRepository: UiRestoreRepository,
    private val recentsRepository: RecentsRepository,
    private val statisticsRepository: StatisticsRepository,
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SearchExecutionService {
    @Volatile private var runtimeSettings = AppSettings()
    private var availableSourcesSnapshot = registry.availableSources()
    private val suggestionCoordinator = SearchSuggestionCoordinator(
        registry = registry,
        tagSuggestionStore = tagSuggestionStore,
        clock = clock,
        supportedSearchScopes = ::supportedSearchScopes,
        effectiveEnabledSources = ::effectiveEnabledSources,
    )
    private val resolvedPostsByExecution = linkedMapOf<String, LinkedHashMap<PostId, Post>>()
    private val resolveFailuresByExecution = mutableMapOf<String, MutableMap<PostId, ResolveFailureRecord>>()
    private val queryPreparation = UnifiedQueryPreparation(clock)
    private val appliedPersistenceMutex = Mutex()
    private val scrollPersistenceMutex = Mutex()
    private val persistedScrollStateByQuery = mutableMapOf<String, SearchScrollState>()

    @Volatile
    private var initialized = false

    val availableSources: List<SourceKey>
        get() = registry.availableSources().inPresentationOrder()

    val isInitialized: Boolean
        get() = initialized

    internal suspend fun initializeRoute(): SearchInitialization {
        tagSuggestionStore.awaitLoaded()
        runtimeSettings = settingsRepository.observeSettings().first()
        availableSourcesSnapshot = registry.availableSources()
        val modes = listOf(QueryMode.Unified) + availableSources.map(QueryMode::Source)
        val storedByMode = modes.mapNotNull { mode ->
            queryRepository.observeAppliedQuery(modeKey(mode)).first()
                ?.takeIf { query -> isModeAvailable(query.mode) }
                ?.let { query -> modeKey(mode) to query }
        }.toMap()
        val lastApplied = queryRepository.observeAppliedQuery(LAST_ACTIVE_QUERY_KEY).first()
            ?.takeIf { query -> isModeAvailable(query.mode) }
        val restored = if (lastApplied != null) {
            storedByMode[modeKey(lastApplied.mode)] ?: defaultQuery(lastApplied.mode)
        } else {
            storedByMode[modeKey(QueryMode.Unified)] ?: defaultQuery()
        }
        val sanitized = restored.sanitizedForMode(restored.mode)
        val sourceScope = SearchSourceScope.fromQuery(sanitized.query)
        val key = executionKey(sanitized.query, sourceScope)
        initialized = true
        return SearchInitialization(
            query = sanitized.query,
            sourceScope = sourceScope,
            appliedByMode = storedByMode,
            availableSources = availableSources,
            hasExecutedSearch = storedByMode.containsKey(modeKey(sanitized.query.mode)) ||
                uiRestoreRepository.getSearchScrollState(key) != null,
            validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
                .takeIf { sanitized.removedSourceOwnedTerms },
        )
    }

    internal fun updateEnvironment(settings: AppSettings): SearchEnvironmentChange {
        val settingsChanged = runtimeSettings.runtime != settings.runtime
        runtimeSettings = settings
        val current = registry.availableSources()
        val sourcesChanged = current != availableSourcesSnapshot
        availableSourcesSnapshot = current
        return SearchEnvironmentChange(settingsChanged, sourcesChanged, current.inPresentationOrder())
    }

    internal fun supportedSearchScopes(mode: QueryMode): List<FacetedSearchScope> {
        val source = (mode as? QueryMode.Source)?.source ?: return emptyList()
        val adapter = registry.adapterFor(source) as? FacetedSearchSourceAdapter ?: return emptyList()
        return adapter.supportedSearchScopes.sortedWith(SEARCH_SCOPE_COMPARATOR)
    }

    override fun executionKeyFor(query: Query, sourceScope: SearchSourceScope): String {
        return executionKey(query.sanitizedForMode(sourceScope.queryMode()).query, sourceScope)
    }

    override suspend fun executeInitial(
        query: Query,
        sourceScope: SearchSourceScope,
    ): SearchExecutionResult {
        currentCoroutineContext().ensureActive()
        val sanitized = query.sanitizedForMode(sourceScope.queryMode()).query
        val enabled = effectiveEnabledSources(sourceScope)
        val available = availableSources
        val weights = effectiveWeights(enabled)
        val key = executionKey(sanitized, sourceScope)
        return try {
            when (val mode = sanitized.mode) {
                QueryMode.Unified -> executeUnifiedInitial(
                    query = sanitized,
                    sourceScope = sourceScope,
                    executionKey = key,
                    enabledSources = enabled,
                    availableSources = available,
                    weights = weights,
                )
                is QueryMode.Source -> executeSourceInitial(
                    query = sanitized,
                    sourceScope = sourceScope,
                    executionKey = key,
                    mode = mode,
                    enabledSources = enabled,
                    availableSources = available,
                    weights = weights,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            SearchExecutionResult.Failure(
                executionKey = key,
                query = sanitized,
                sourceScope = sourceScope,
                statuses = emptyList(),
                message = if (isPixivUnknownError(error, sanitized)) {
                    PIXIV_UNKNOWN_RETRY_MESSAGE
                } else error.message ?: "Unknown error",
            )
        }
    }

    private suspend fun executeUnifiedInitial(
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
        enabledSources: Set<SourceKey>,
        availableSources: List<SourceKey>,
        weights: Map<SourceKey, Double>,
    ): SearchExecutionResult {
        val excluded = excludedStatuses(sourceScope, availableSources, enabledSources)
        if (enabledSources.isEmpty()) {
            val continuation = continuation(
                executionKey, query, sourceScope, enabledSources, availableSources, weights,
            )
            return SearchExecutionResult.Success(
                executionKey, query, sourceScope, emptyList(), excluded, continuation,
            )
        }
        val overrides = buildUnifiedQueryOverrides(query, enabledSources)
        currentCoroutineContext().ensureActive()
        val result = registry.unifiedOrchestrator().search(
            query = query,
            enabledSources = enabledSources,
            pageTokens = emptyMap(),
            weights = weights,
            queryOverridesBySource = overrides,
            prepareQuery = { source, sourceQuery ->
                if (source == SourceKey.GELBOORU) {
                    queryPreparation.prepareGelbooru(requireNotNull(registry.adapterFor(source)), sourceQuery)
                } else sourceQuery
            },
        )
        currentCoroutineContext().ensureActive()
        val statuses = mergeStatuses(excluded, result.statuses)
        if (isPixivUnknownFailure(result.items, statuses)) {
            return SearchExecutionResult.Failure(
                executionKey, query, sourceScope, statuses, PIXIV_UNKNOWN_RETRY_MESSAGE,
            )
        }
        rememberSeenTags(result.items)
        return SearchExecutionResult.Success(
            executionKey = executionKey,
            query = query,
            sourceScope = sourceScope,
            posts = result.items,
            statuses = statuses,
            continuation = continuation(
                executionKey, query, sourceScope, enabledSources, availableSources, weights,
                unifiedPageTokens = result.nextPageTokens,
                unifiedQueryOverrides = result.queriesBySource,
            ),
        )
    }

    private suspend fun executeSourceInitial(
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
        mode: QueryMode.Source,
        enabledSources: Set<SourceKey>,
        availableSources: List<SourceKey>,
        weights: Map<SourceKey, Double>,
    ): SearchExecutionResult {
        if (!isModeAvailable(mode)) {
            return SearchExecutionResult.Success(
                executionKey = executionKey,
                query = query,
                sourceScope = sourceScope,
                posts = emptyList(),
                statuses = listOf(
                    SourceRunStatus(
                        source = mode.source,
                        state = SourceRunState.EXCLUDED,
                        errorMessage = "Source not available in this build",
                    ),
                ),
                continuation = continuation(
                    executionKey, query, sourceScope, enabledSources, availableSources, weights,
                ),
            )
        }
        val adapter = requireNotNull(registry.adapterFor(mode.source)) { "No adapter for ${mode.source}" }
        val page = registry.unifiedOrchestrator().searchSource(adapter, query, null)
        currentCoroutineContext().ensureActive()
        rememberSeenTags(page.items)
        return SearchExecutionResult.Success(
            executionKey = executionKey,
            query = query,
            sourceScope = sourceScope,
            posts = page.items,
            statuses = listOf(SourceRunStatus(mode.source, SourceRunState.SUCCESS)),
            continuation = continuation(
                executionKey, query, sourceScope, enabledSources, availableSources, weights,
                sourcePageToken = page.nextPageToken,
            ),
        )
    }

    override suspend fun executePage(continuation: SearchContinuation): SearchPageResult {
        currentCoroutineContext().ensureActive()
        return try {
            when (val mode = continuation.query.mode) {
                QueryMode.Unified -> executeUnifiedPage(continuation)
                is QueryMode.Source -> executeSourcePage(continuation, mode)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            SearchPageResult.Failure(
                executionKey = continuation.executionKey,
                statuses = emptyList(),
                message = if (isPixivUnknownError(error, continuation.query)) {
                    PIXIV_UNKNOWN_RETRY_MESSAGE
                } else error.message ?: "Could not load more results",
            )
        }
    }

    private suspend fun executeUnifiedPage(continuation: SearchContinuation): SearchPageResult {
        val excluded = excludedStatuses(
            continuation.sourceScope,
            continuation.availableSources,
            continuation.enabledSources,
        )
        val pageable = continuation.enabledSources.filterTo(mutableSetOf()) { source ->
            !continuation.unifiedPageTokens[source].isNullOrBlank()
        }
        if (pageable.isEmpty()) {
            return SearchPageResult.Success(
                continuation.executionKey,
                emptyList(),
                excluded,
                continuation.copy(unifiedPageTokens = emptyMap()),
            )
        }
        val result = registry.unifiedOrchestrator().search(
            query = continuation.query,
            enabledSources = pageable,
            pageTokens = continuation.unifiedPageTokens.filterKeys { it in pageable },
            weights = SourceWeightNormalization.normalize(pageable, continuation.weights),
            queryOverridesBySource = continuation.unifiedQueryOverrides.filterKeys { it in pageable },
        )
        currentCoroutineContext().ensureActive()
        val statuses = mergeStatuses(excluded, result.statuses)
        if (isPixivUnknownFailure(result.items, statuses)) {
            return SearchPageResult.Failure(
                continuation.executionKey, statuses, PIXIV_UNKNOWN_RETRY_MESSAGE,
            )
        }
        rememberSeenTags(result.items)
        val nextTokens = continuation.unifiedPageTokens.toMutableMap().apply {
            pageable.forEach { source -> put(source, null) }
            putAll(result.nextPageTokens)
        }
        return SearchPageResult.Success(
            continuation.executionKey,
            result.items,
            statuses,
            continuation.copy(unifiedPageTokens = nextTokens),
        )
    }

    private suspend fun executeSourcePage(
        continuation: SearchContinuation,
        mode: QueryMode.Source,
    ): SearchPageResult {
        val token = continuation.sourcePageToken
        if (token.isNullOrBlank()) {
            return SearchPageResult.Success(
                continuation.executionKey,
                emptyList(),
                emptyList(),
                continuation.copy(sourcePageToken = null),
            )
        }
        val adapter = requireNotNull(registry.adapterFor(mode.source)) { "No adapter for ${mode.source}" }
        val page = registry.unifiedOrchestrator().searchSource(adapter, continuation.query, token)
        currentCoroutineContext().ensureActive()
        rememberSeenTags(page.items)
        return SearchPageResult.Success(
            continuation.executionKey,
            page.items,
            listOf(SourceRunStatus(mode.source, SourceRunState.SUCCESS)),
            continuation.copy(sourcePageToken = page.nextPageToken),
        )
    }

    override suspend fun persistAppliedSearch(
        query: Query,
        sourceScope: SearchSourceScope,
        executionKey: String,
    ) {
        appliedPersistenceMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (sourceScope is SearchSourceScope.Temporary) return@withLock
            queryRepository.upsertAppliedQueries(mapOf(modeKey(query.mode) to query, LAST_ACTIVE_QUERY_KEY to query))
            currentCoroutineContext().ensureActive()
            uiRestoreRepository.setSearchScrollState(executionKey, SearchScrollState(0, 0))
        }
    }

    override suspend fun recordAcceptedSearch(query: Query, sourceScope: SearchSourceScope, executionKey: String) {
        val kind = when (sourceScope) {
            SearchSourceScope.GlobalUnified -> RecentSearchKind.UNIFIED
            is SearchSourceScope.Single -> RecentSearchKind.SOURCE
            is SearchSourceScope.Temporary -> RecentSearchKind.MULTI_SEARCH
        }
        val sources = effectiveEnabledSources(sourceScope).inPresentationOrder()
        runCatchingPreservingCancellation { recentsRepository.recordSearch(query, executionKey, kind, sources) }
        runCatchingPreservingCancellation { statisticsRepository.recordSearch(sources.toSet()) }
    }

    internal suspend fun fetchAutocomplete(
        query: Query,
        sourceScope: SearchSourceScope,
        selectedScope: FacetedSearchScope,
        input: String,
        trending: List<TagSuggestion>,
        onUpdate: suspend (SearchAutocompleteResult) -> Unit = {},
    ): SearchAutocompleteResult = suggestionCoordinator.fetchAutocomplete(
        query,
        sourceScope,
        selectedScope,
        input,
        trending,
        onUpdate,
    )

    internal fun cachedAutocomplete(
        query: Query,
        sourceScope: SearchSourceScope,
        selectedScope: FacetedSearchScope,
        input: String,
        trending: List<TagSuggestion>,
    ): SearchAutocompleteResult = suggestionCoordinator.cachedAutocomplete(
        query,
        sourceScope,
        selectedScope,
        input,
        trending,
    )

    internal suspend fun fetchTrending(
        query: Query,
        sourceScope: SearchSourceScope,
        forceRefresh: Boolean = false,
    ): List<TagSuggestion> = suggestionCoordinator.fetchTrending(query, sourceScope, forceRefresh)

    internal fun cachedTrending(
        query: Query,
        sourceScope: SearchSourceScope,
    ): List<TagSuggestion> = suggestionCoordinator.cachedTrending(query, sourceScope)

    internal fun tagVideoCount(
        source: SourceKey,
        tag: String,
        autocomplete: List<TagSuggestion>,
        trending: List<TagSuggestion>,
    ): Int? {
        val normalized = tag.trim()
        if (normalized.isBlank()) return null
        return tagSuggestionStore.get(source, TAG_LOOKUP_LIMIT)
            .firstOrNull { sourceTagsMatch(source, it.text, normalized) }?.count
            ?: autocomplete.firstOrNull { sourceTagsMatch(source, it.text, normalized) }?.count
            ?: trending.firstOrNull { sourceTagsMatch(source, it.text, normalized) }?.count
    }

    fun tagVideoCount(source: SourceKey, tag: String): Int? {
        return tagVideoCount(source, tag, emptyList(), emptyList())
    }

    internal suspend fun fetchTagVideoCounts(
        source: SourceKey,
        tags: List<String>,
        autocomplete: List<TagSuggestion>,
        trending: List<TagSuggestion>,
    ): Map<String, Int?> {
        val requested = tags.map(String::trim).filter(String::isNotBlank).distinct()
        if (requested.isEmpty()) return emptyMap()
        val resolved = requested.associateWith {
            tagVideoCount(source, it, autocomplete, trending)
        }.toMutableMap()
        var missing = requested.filter { resolved[it] == null }
        val adapter = registry.adapterFor(source) ?: return resolved
        if (adapter is TagCountLookupSourceAdapter && missing.isNotEmpty()) {
            val counts = runCatchingPreservingCancellation {
                adapter.fetchTagCounts(missing.map { autocompletePrefixForSource(source, it) })
            }.getOrDefault(emptyMap())
            if (counts.isNotEmpty()) {
                tagSuggestionStore.put(
                    source,
                    counts.map { (text, count) -> TagSuggestion(text, "tag_count_lookup", count) },
                    TagSuggestionOrigin.COUNT_LOOKUP,
                )
                missing.forEach { tag ->
                    counts.entries.firstOrNull { sourceTagsMatch(source, it.key, tag) }
                        ?.value?.let { resolved[tag] = it }
                }
            }
            missing = requested.filter { resolved[it] == null }
        }
        missing.forEach { tag ->
            val fetched = runCatchingPreservingCancellation {
                adapter.autocompleteTags(autocompletePrefixForSource(source, tag), TAG_FETCH_LIMIT)
            }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                tagSuggestionStore.put(source, fetched, TagSuggestionOrigin.AUTOCOMPLETE)
            }
            val count = fetched.firstOrNull { sourceTagsMatch(source, it.text, tag) }?.count
                ?: tagVideoCount(source, tag, autocomplete, trending)
            if (count != null) resolved[tag] = count
        }
        return resolved
    }

    suspend fun fetchTagVideoCounts(source: SourceKey, tags: List<String>): Map<String, Int?> {
        return fetchTagVideoCounts(source, tags, emptyList(), emptyList())
    }

    suspend fun resolveNhentaiGalleryById(galleryId: String): Post? {
        val id = galleryId.trim().takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        return registry.adapterFor(SourceKey.NHENTAI)
            ?.resolvePost(PostId(SourceKey.NHENTAI, id))
    }

    internal suspend fun resolvePostForSearch(postId: PostId, executionKey: String): Post? {
        resolvedPostsByExecution[executionKey]?.get(postId)?.let { return it }
        if (shouldDeferResolve(postId, executionKey)) return null
        val adapter = registry.adapterFor(postId.source) ?: return null
        return try {
            val resolved = adapter.resolvePost(postId) ?: return null
            rememberResolvedPost(resolved, executionKey)
            resolved
        } catch (error: SourceAdapterException) {
            if (error.reason != SourceFailureReason.RATE_LIMITED) throw error
            rememberResolveFailure(postId, error.reason, executionKey)
            null
        }
    }

    suspend fun recoverPostMedia(post: Post, failedMedia: ImageRef): Post? {
        return recoverRemoteMedia(registry, post, failedMedia)
    }

    suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        uiRestoreRepository.setViewerLaunchContext(context)
    }

    suspend fun persistSearchScrollState(
        index: Int,
        offsetPx: Int,
        queryHash: String,
    ) {
        val state = SearchScrollState(index.coerceAtLeast(0), offsetPx.coerceAtLeast(0))
        withContext(NonCancellable) {
            scrollPersistenceMutex.withLock {
                if (persistedScrollStateByQuery[queryHash] == state) return@withLock
                uiRestoreRepository.setSearchScrollState(queryHash, state)
                persistedScrollStateByQuery[queryHash] = state
            }
        }
    }

    internal suspend fun restoreSearchScrollState(
        queryHash: String,
        sourceScope: SearchSourceScope,
    ): SearchScrollState? {
        if (sourceScope is SearchSourceScope.Temporary) return null
        val restored = uiRestoreRepository.getSearchScrollState(queryHash)
        if (restored != null) scrollPersistenceMutex.withLock {
            persistedScrollStateByQuery[queryHash] = restored
        }
        return restored
    }

    private suspend fun buildUnifiedQueryOverrides(
        query: Query,
        enabledSources: Set<SourceKey>,
    ): Map<SourceKey, Query> {
        if (query.mode != QueryMode.Unified) return emptyMap()
        val includeGroups = query.effectiveIncludeTermGroups
            .filter(SearchTermGroup::isPortableGeneralTagGroup)
        val exclude = query.excludeTerms.filter(SearchTerm::isPortableGeneralTag)
        val overrides = mutableMapOf<SourceKey, Query>()
        if (SourceKey.PIXIV in enabledSources) {
            val groups = includeGroups.map { group ->
                SearchTermGroup(
                    resolvePixivCompatibilityTags(group.terms.map(SearchTerm::value)).map(::SearchTerm)
                )
            }
            overrides[SourceKey.PIXIV] = query.withIncludeTermGroups(groups).copy(
                excludeTerms = resolvePixivCompatibilityTags(exclude.map(SearchTerm::value)).map(::SearchTerm),
            )
        }
        return overrides
    }

    private fun resolvePixivCompatibilityTags(tags: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return tags.mapNotNull { raw ->
            var value = raw.trim().removePrefix("-").replace('_', ' ')
                .replace(WHITESPACE_REGEX, " ").trim()
            while (value.isNotBlank() && PIXIV_TRAILING_PARENTHESIS_REGEX.containsMatchIn(value)) {
                value = value.replace(PIXIV_TRAILING_PARENTHESIS_REGEX, "").trim()
            }
            value.takeIf { it.isNotBlank() && seen.add(normalizeMatchToken(it)) }
        }
    }

    private suspend fun rememberSeenTags(posts: List<Post>) = withContext(computationDispatcher) {
        posts.groupBy { it.id.source }.forEach { (source, sourcePosts) ->
            val suggestions = sourcePosts.asSequence()
                .flatMap { post -> recommendationTaxonomyFor(post).asSequence() }
                .map { term ->
                    FacetedTagSuggestion(
                        text = normalizeFavoriteTagForStorage(source, term.value),
                        facet = SearchFacet.TAG,
                        sourceNamespace = term.sourceNamespace,
                    )
                }
                .filter { it.text.isNotBlank() }
                .distinctBy { Triple(it.facet, it.sourceNamespace, sourceTagKey(source, it.text)) }
                .take(SEEN_TAGS_PER_SOURCE_INGEST_LIMIT)
                .toList()
            if (suggestions.isNotEmpty()) {
                tagSuggestionStore.putFaceted(source, suggestions, TagSuggestionOrigin.SEEN)
            }
        }
    }

    private fun rememberResolvedPost(post: Post, executionKey: String) {
        val bucket = resolvedPostsByExecution.getOrPut(executionKey) { linkedMapOf() }
        bucket.remove(post.id)
        bucket[post.id] = post
        while (bucket.size > MAX_RESOLVED_POST_OVERRIDES_PER_QUERY) {
            bucket.remove(bucket.entries.firstOrNull()?.key ?: break)
        }
        while (resolvedPostsByExecution.size > MAX_REMEMBERED_QUERY_OVERRIDES) {
            resolvedPostsByExecution.remove(resolvedPostsByExecution.entries.firstOrNull()?.key ?: break)
        }
        resolveFailuresByExecution[executionKey]?.remove(post.id)
    }

    private fun rememberResolveFailure(
        postId: PostId,
        reason: SourceFailureReason,
        executionKey: String,
    ) {
        val now = clock()
        val bucket = resolveFailuresByExecution.getOrPut(executionKey) { linkedMapOf() }
        val previous = bucket[postId]
        val backoff = if (previous?.reason == SourceFailureReason.RATE_LIMITED &&
            reason == SourceFailureReason.RATE_LIMITED &&
            now - previous.lastFailureAtMs <= RATE_LIMIT_REPEAT_WINDOW_MS
        ) RATE_LIMIT_BACKOFF_REPEAT_MS else RATE_LIMIT_BACKOFF_FIRST_MS
        bucket[postId] = ResolveFailureRecord(now, now + backoff, reason)
        while (bucket.size > MAX_RESOLVED_POST_OVERRIDES_PER_QUERY) {
            bucket.remove(bucket.entries.firstOrNull()?.key ?: break)
        }
    }

    private fun shouldDeferResolve(postId: PostId, executionKey: String): Boolean {
        val record = resolveFailuresByExecution[executionKey]?.get(postId) ?: return false
        return record.reason == SourceFailureReason.RATE_LIMITED && clock() < record.backoffUntilMs
    }

    private fun effectiveEnabledSources(scope: SearchSourceScope): Set<SourceKey> {
        val available = registry.availableSources()
        return when (scope) {
            SearchSourceScope.GlobalUnified -> runtimeSettings.runtime.enabledSources.intersect(available)
            is SearchSourceScope.Single -> setOf(scope.source).intersect(available)
            is SearchSourceScope.Temporary -> scope.sources.toSet().intersect(available)
        }
    }

    private fun effectiveWeights(sources: Set<SourceKey>): Map<SourceKey, Double> =
        SourceWeightNormalization.normalize(sources, runtimeSettings.runtime.sourceWeights)

    private fun excludedStatuses(
        scope: SearchSourceScope,
        available: List<SourceKey>,
        enabled: Set<SourceKey>,
    ): List<SourceRunStatus> {
        if (scope != SearchSourceScope.GlobalUnified) return emptyList()
        return available.filterNot { it in enabled }.map { source ->
            SourceRunStatus(source, SourceRunState.EXCLUDED, errorMessage = "Disabled in settings")
        }
    }

    private fun mergeStatuses(
        first: List<SourceRunStatus>,
        second: List<SourceRunStatus>,
    ): List<SourceRunStatus> = (first + second).associateBy { it.source }.values.sortedBy { it.source.name }

    private fun isPixivUnknownFailure(posts: List<Post>, statuses: List<SourceRunStatus>): Boolean =
        posts.isEmpty() && statuses.any { status ->
            status.source == SourceKey.PIXIV && status.state == SourceRunState.FAILED &&
                (status.failureReason == SourceFailureReason.UNKNOWN ||
                    status.errorMessage?.contains("PIXIV_UNKNOWN", ignoreCase = true) == true)
        }

    private fun isPixivUnknownError(error: Throwable, query: Query): Boolean {
        if (error is SourceAdapterException && error.reason == SourceFailureReason.UNKNOWN) {
            return query.mode == QueryMode.Unified || query.mode == QueryMode.Source(SourceKey.PIXIV)
        }
        return error.message.orEmpty().contains("PIXIV_UNKNOWN", ignoreCase = true)
    }

    private fun executionKey(query: Query, scope: SearchSourceScope): String {
        val queryHash = QueryHash.from(query)
        val temporary = scope as? SearchSourceScope.Temporary ?: return queryHash
        return "$queryHash|temporary-sources:${temporary.sources.joinToString(",") { it.name }}"
    }

    private fun continuation(
        executionKey: String,
        query: Query,
        scope: SearchSourceScope,
        enabled: Set<SourceKey>,
        available: List<SourceKey>,
        weights: Map<SourceKey, Double>,
        unifiedPageTokens: Map<SourceKey, String?> = emptyMap(),
        unifiedQueryOverrides: Map<SourceKey, Query> = emptyMap(),
        sourcePageToken: String? = null,
    ) = SearchContinuation(
        executionKey,
        query,
        scope,
        enabled,
        available,
        weights,
        unifiedPageTokens,
        unifiedQueryOverrides,
        sourcePageToken,
    )

    private fun isModeAvailable(mode: QueryMode): Boolean = when (mode) {
        QueryMode.Unified -> true
        is QueryMode.Source -> mode.source in registry.availableSources()
    }

    private fun autocompletePrefixForSource(source: SourceKey, input: String): String {
        val normalized = parseScopedInput(input).value
        return when (source) {
            SourceKey.GELBOORU, SourceKey.IWARA, SourceKey.RULE34XXX -> normalizeGelbooruToken(normalized)
            else -> normalized
        }
    }
}
