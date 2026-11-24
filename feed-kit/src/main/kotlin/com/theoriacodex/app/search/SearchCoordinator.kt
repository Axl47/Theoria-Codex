package com.theoriacodex.app.search

import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.app.feed.FeedCursor
import com.theoriacodex.app.feed.mergeFeedStatuses
import com.theoriacodex.app.feed.nextPage
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.post.FileMirrorIndex
import com.theoriacodex.app.source.PostResolutionFlights
import com.theoriacodex.app.search.state.modeKey
import com.theoriacodex.app.search.state.queryMode
import com.theoriacodex.app.source.inPresentationOrder
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SearchFirstPageStore
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.SearchedTagKey
import com.theoriacodex.data.repository.searchedTerms
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedSearchSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.normalizedForMode
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import com.theoriacodex.domain.query.SearchTermLimit
import com.theoriacodex.domain.tags.pixivSearchToken
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import com.theoriacodex.domain.tags.sourceAutocompletePrefix
import com.theoriacodex.domain.tags.normalizeMatchToken
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("LargeClass")
class SearchCoordinator(
    private val registry: SourceAdapterRegistry,
    private val queryRepository: QueryRepository,
    private val settingsRepository: SettingsRepository,
    private val uiRestoreRepository: UiRestoreRepository,
    private val recentsRepository: RecentsRepository,
    private val statisticsRepository: StatisticsRepository,
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    private val firstPageStore: SearchFirstPageStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    seenTagScope: CoroutineScope = CoroutineScope(SupervisorJob() + computationDispatcher),
    private val postResolutions: PostResolutionFlights = PostResolutionFlights(),
    private val fileMirrors: FileMirrorIndex = FileMirrorIndex(),
) : SearchExecutionService {
    private val seenTags = SeenTagRecorder(statisticsRepository, tagSuggestionStore, computationDispatcher, seenTagScope)

    private val searchCounts: StateFlow<Map<SearchedTagKey, Long>> = statisticsRepository.observeStatistics()
        .map { statistics -> statistics.searchesByTag }
        .distinctUntilChanged()
        .catch { emit(emptyMap()) }
        .stateIn(seenTagScope, SharingStarted.Eagerly, emptyMap())

    private fun listed(posts: List<Post>) {
        seenTags.record(posts)
        fileMirrors.learn(posts)
    }
    val suggestions = SearchSuggestionCoordinator(
        registry = registry,
        tagSuggestionStore = tagSuggestionStore,
        clock = clock,
        supportedSearchScopes = ::supportedSearchScopes,
        searchCounts = searchCounts::value,
    )
    private val tagCounts = TagCountCoordinator(registry, tagSuggestionStore, ::autocompletePrefixForSource,
        clock = clock, dispatcher = computationDispatcher)
    private val searchTermCounts = SearchTermCountCoordinator(registry, clock, computationDispatcher)
    private val resolvedPostsByExecution = linkedMapOf<String, LinkedHashMap<PostId, Post>>()
    private val resolveFailuresByExecution = mutableMapOf<String, MutableMap<PostId, ResolveFailureRecord>>()
    private val queryPreparation = UnifiedQueryPreparation(
        isKnownTag = { source, tag -> tagSuggestionStore.getCount(source, tag) != null },
        clock = clock,
    )
    private val appliedPersistenceMutex = Mutex()
    private val scrollPersistenceMutex = Mutex()
    private val persistedScrollStateByQuery = mutableMapOf<String, SearchScrollState>()

    val availableSources: List<SourceKey>
        get() = registry.availableSources().inPresentationOrder()

    suspend fun initializeRoute(): SearchInitialization {
        tagSuggestionStore.awaitLoaded()
        val settings = settingsRepository.observeSettings().first()
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
        val sanitized = restored.normalizedForMode(restored.mode)
        val sourceScope = SearchSourceScope.fromQuery(sanitized.query)
        val key = searchExecutionKey(sanitized.query, sourceScope)
        return SearchInitialization(
            query = sanitized.query,
            sourceScope = sourceScope,
            appliedByMode = storedByMode,
            availableSources = availableSources,
            hasExecutedSearch = storedByMode.containsKey(modeKey(sanitized.query.mode)) ||
                uiRestoreRepository.getSearchScrollState(key) != null,
            validationMessage = UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE
                .takeIf { sanitized.removedSourceOwnedTerms },
            runtime = settings.runtime,
            scopesBySource = searchScopesBySource(),
            exactTagSources = exactTagSources(),
        )
    }

    fun exactTagSources(): Map<SourceKey, String> = registry.availableSources()
        .filter { source -> registry.adapterFor(source)?.capabilities?.requiresExactTags == true }
        .associateWith { source -> source.displayName() }

    private suspend fun prepareExactTags(source: SourceKey, query: Query): Query {
        val adapter = registry.adapterFor(source)?.takeIf { it.capabilities.requiresExactTags } ?: return query
        return queryPreparation.prepareExactTags(adapter, query)
    }

    fun sourceTermLimits(): Map<SourceKey, SearchTermLimit> = registry.availableSources().mapNotNull { source ->
        registry.adapterFor(source)?.capabilities?.searchTermLimit?.let { source to it }
    }.toMap()

    fun searchScopesBySource(): Map<SourceKey, List<FacetedSearchScope>> = availableSources
        .associateWith { source -> supportedSearchScopes(QueryMode.Source(source)) }
        .filterValues { it.isNotEmpty() }

    fun supportedSearchScopes(mode: QueryMode): List<FacetedSearchScope> {
        val source = (mode as? QueryMode.Source)?.source ?: return emptyList()
        val adapter = registry.adapterFor(source) as? FacetedSearchSourceAdapter ?: return emptyList()
        return adapter.supportedSearchScopes.sortedWith(SEARCH_SCOPE_COMPARATOR)
    }

    override suspend fun executeInitial(
        query: Query,
        sourceScope: SearchSourceScope,
        sources: SearchSources,
        onPartial: (suspend (SearchExecutionResult.Success) -> Unit)?,
    ): SearchExecutionResult = executeRoot(query, sourceScope, sources, onPartial)

    override suspend fun cachedFirstPage(executionKey: String, sources: Set<SourceKey>): List<Post> =
        firstPageStore?.load(executionKey).orEmpty().filter { it.id.source in sources }.also(fileMirrors::learn)

    override suspend fun rememberFirstPage(executionKey: String, posts: List<Post>) {
        firstPageStore?.save(executionKey, posts)
    }

    private suspend fun executeRoot(
        query: Query,
        sourceScope: SearchSourceScope,
        sources: SearchSources,
        onPartial: (suspend (SearchExecutionResult.Success) -> Unit)?,
    ): SearchExecutionResult {
        currentCoroutineContext().ensureActive()
        val sanitized = query.normalizedForMode(sourceScope.queryMode()).query
        val base = firstPage(sanitized, sourceScope, sources)
        return runCatchingPreservingCancellation {
            when (val mode = sanitized.mode) {
                QueryMode.Unified -> executeUnifiedInitial(base, onPartial)
                is QueryMode.Source -> executeSourceInitial(base, mode)
            }
        }.getOrElse { error ->
            SearchExecutionResult.Failure(
                executionKey = base.executionKey,
                query = sanitized,
                sourceScope = sourceScope,
                statuses = thrownStatuses(error, sanitized),
                message = thrownMessage(error, sanitized, fallback = "Unknown error"),
            )
        }
    }

    private fun firstPage(query: Query, scope: SearchSourceScope, sources: SearchSources) = SearchContinuation(
        executionKey = searchExecutionKey(query, scope),
        sourceScope = scope,
        enabledSources = sources.enabled.intersect(registry.availableSources()),
        availableSources = availableSources,
        cursor = FeedCursor(query, weights = sources.weights),
    )

    private suspend fun executeUnifiedInitial(
        base: SearchContinuation,
        onPartial: (suspend (SearchExecutionResult.Success) -> Unit)?,
    ): SearchExecutionResult {
        val enabled = base.enabledSources
        val excluded = excludedStatuses(base.sourceScope, base.availableSources, enabled)
        if (enabled.isEmpty()) return base.found(emptyList(), excluded)
        val overrides = buildUnifiedQueryOverrides(base.query, enabled)
        currentCoroutineContext().ensureActive()
        val result = registry.unifiedOrchestrator().search(
            query = base.query,
            enabledSources = enabled,
            pageTokens = emptyMap(),
            weights = base.cursor.weights,
            queryOverridesBySource = overrides,
            prepareQuery = ::prepareExactTags,
            onPartial = onPartial?.let { publish ->
                { partial ->
                    fileMirrors.learn(partial.items)
                    publish(base.found(partial.items, mergeFeedStatuses(excluded, partial.statuses)))
                }
            },
        )
        currentCoroutineContext().ensureActive()
        val statuses = mergeFeedStatuses(excluded, result.statuses)
        val next = base.accept(result, enabled)
        if (statuses.none { it.state == SourceRunState.SUCCESS }) {
            return SearchExecutionResult.Failure(base.executionKey, base.query, base.sourceScope, statuses,
                unifiedFailureMessage(result.items, statuses), next)
        }
        listed(result.items)
        return base.found(result.items, statuses, next)
    }

    private suspend fun executeSourceInitial(base: SearchContinuation, mode: QueryMode.Source): SearchExecutionResult {
        if (!isModeAvailable(mode)) {
            return base.found(
                posts = emptyList(),
                statuses = listOf(
                    SourceRunStatus(
                        source = mode.source,
                        state = SourceRunState.EXCLUDED,
                        errorMessage = "Source not available in this build",
                    ),
                ),
            )
        }
        searchTermLimitNotices(base.query, listOf(mode.source), sourceTermLimits()).firstOrNull()?.let { notice ->
            return SearchExecutionResult.Failure(base.executionKey, base.query, base.sourceScope, emptyList(), notice.message())
        }
        val page = fetchSource(base, mode.source)
        return base.found(page.items, page.statuses, base.accept(page, setOf(mode.source)))
    }

    override suspend fun retrySource(
        query: Query,
        scope: SearchSourceScope,
        sources: SearchSources,
        previous: SearchContinuation?,
        source: SourceKey,
    ): SearchPageResult {
        val base = previous ?: firstPage(query, scope, sources)
        val enabled = sources.enabled.intersect(registry.availableSources())
        if (source !in enabled) return SearchPageResult.Failure(
            base.executionKey, emptyList(), "Source is no longer available",
        )
        if (query.mode is QueryMode.Source) return retrySingleSource(base, source)
        val ran = base.cursor.queriesBySource
        val overrides = ran.ifEmpty { buildUnifiedQueryOverrides(query, enabled) }
        val result = registry.unifiedOrchestrator().search(
            query = query,
            enabledSources = setOf(source),
            pageTokens = mapOf(source to base.cursor.pageTokens[source]),
            weights = mapOf(source to 1.0),
            queryOverridesBySource = overrides.filterKeys { it == source },
            prepareQuery = { key, prepared -> if (key in ran) prepared else prepareExactTags(key, prepared) },
        )
        currentCoroutineContext().ensureActive()
        if (result.statuses.none { it.state == SourceRunState.SUCCESS }) {
            return SearchPageResult.Failure(
                base.executionKey, result.statuses, sourceFailureMessage(result.statuses, fallback = "Source could not be retried"),
            )
        }
        listed(result.items)
        return SearchPageResult.Success(base.executionKey, result.items, result.statuses, base.accept(result, setOf(source)))
    }

    private suspend fun retrySingleSource(base: SearchContinuation, source: SourceKey): SearchPageResult = runCatchingExceptionsPreservingCancellation {
        val page = fetchSource(base, source, timeoutMs = SINGLE_SOURCE_RETRY_TIMEOUT_MS)
        SearchPageResult.Success(base.executionKey, page.items, page.statuses, base.accept(page, setOf(source)))
    }.getOrElse { error ->
        SearchPageResult.Failure(base.executionKey, listOf(sourceFailureStatus(source, error)),
            error.message ?: "Could not retry source")
    }

    private fun thrownStatuses(error: Throwable, query: Query): List<SourceRunStatus> =
        (query.mode as? QueryMode.Source)?.let { mode -> listOf(sourceFailureStatus(mode.source, error)) }.orEmpty()

    private fun thrownMessage(error: Throwable, query: Query, fallback: String): String =
        if (isPixivUnknownError(error, query)) PIXIV_UNKNOWN_RETRY_MESSAGE else error.message ?: fallback

    private fun sourceFailureStatus(source: SourceKey, error: Throwable): SourceRunStatus {
        val reason = (error as? SourceAdapterException)?.reason ?: SourceFailureReason.UNKNOWN
        Diagnostics.report("source", "${source.id} failed: $reason", error)
        return SourceRunStatus(source, SourceRunState.FAILED, failureReason = reason, errorMessage = error.message)
    }

    override suspend fun executePage(continuation: SearchContinuation): SearchPageResult {
        currentCoroutineContext().ensureActive()
        return runCatchingPreservingCancellation {
            when (val mode = continuation.query.mode) {
                QueryMode.Unified -> executeUnifiedPage(continuation)
                is QueryMode.Source -> executeSourcePage(continuation, mode.source)
            }
        }.getOrElse { error ->
            SearchPageResult.Failure(
                executionKey = continuation.executionKey,
                statuses = thrownStatuses(error, continuation.query),
                message = thrownMessage(error, continuation.query, fallback = "Could not load more results"),
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
            source !in continuation.failedSources && !continuation.cursor.pageTokens[source].isNullOrBlank()
        }
        if (pageable.isEmpty()) return SearchPageResult.Success(continuation.executionKey, emptyList(), excluded, continuation.ended())
        val result = registry.unifiedOrchestrator().nextPage(continuation.cursor, pageable)
        currentCoroutineContext().ensureActive()
        val statuses = mergeFeedStatuses(excluded, result.statuses)
        if (statuses.none { it.state == SourceRunState.SUCCESS }) {
            return SearchPageResult.Failure(continuation.executionKey, statuses, unifiedFailureMessage(result.items, statuses))
        }
        listed(result.items)
        val next = continuation.accept(result, pageable)
        val gone = pageable - registry.availableSources()
        return SearchPageResult.Success(continuation.executionKey, result.items, statuses,
            next.copy(cursor = next.cursor.copy(pageTokens = next.cursor.pageTokens - gone)))
    }

    private suspend fun executeSourcePage(continuation: SearchContinuation, source: SourceKey): SearchPageResult {
        if (continuation.cursor.pageTokens[source].isNullOrBlank()) {
            return SearchPageResult.Success(continuation.executionKey, emptyList(), emptyList(), continuation.ended())
        }
        val page = fetchSource(continuation, source)
        return SearchPageResult.Success(continuation.executionKey, page.items, page.statuses, continuation.accept(page, setOf(source)))
    }

    private suspend fun fetchSource(
        continuation: SearchContinuation,
        source: SourceKey,
        timeoutMs: Long? = null,
    ): UnifiedSearchResult {
        val adapter = requireNotNull(registry.adapterFor(source)) { "No adapter for $source" }
        val token = continuation.cursor.pageTokens[source]
        val page = registry.unifiedOrchestrator().searchSource(adapter, continuation.query, token, timeoutMs)
        currentCoroutineContext().ensureActive()
        listed(page.items)
        return UnifiedSearchResult(page.items, mapOf(source to page.nextPageToken), listOf(SourceRunStatus(source, SourceRunState.SUCCESS)))
    }

    private fun SearchContinuation.found(
        posts: List<Post>,
        statuses: List<SourceRunStatus>,
        next: SearchContinuation = this,
    ) = SearchExecutionResult.Success(executionKey, query, sourceScope, posts, statuses, next)

    private fun SearchContinuation.ended() = copy(cursor = cursor.copy(pageTokens = emptyMap()))

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
            persistSearchScrollState(0, 0, executionKey)
        }
    }

    override suspend fun recordAcceptedSearch(result: SearchExecutionResult) {
        val scope = result.sourceScope
        val kind = when (scope) {
            SearchSourceScope.GlobalUnified -> RecentSearchKind.UNIFIED
            is SearchSourceScope.Single -> RecentSearchKind.SOURCE
            is SearchSourceScope.Temporary -> RecentSearchKind.MULTI_SEARCH
        }
        val captured = when (result) {
            is SearchExecutionResult.Success -> result.continuation.enabledSources
            is SearchExecutionResult.Failure -> result.continuation?.enabledSources.orEmpty()
        }
        val sources = when (scope) {
            is SearchSourceScope.Single -> listOf(scope.source)
            is SearchSourceScope.Temporary -> scope.sources
            SearchSourceScope.GlobalUnified -> captured.inPresentationOrder()
        }
        runCatchingPreservingCancellation {
            recentsRepository.recordSearch(result.query, result.executionKey, kind, sources)
        }
        if (result.completedSuccessfully) runCatchingPreservingCancellation {
            statisticsRepository.recordSearch(
                sources = result.statuses.filter { it.state != SourceRunState.EXCLUDED }.mapTo(mutableSetOf()) { it.source },
                terms = result.query.searchedTerms(),
            )
        }
    }

    fun tagVideoCount(source: SourceKey, tag: String): Int? =
        tag.trim().takeIf(String::isNotBlank)?.let { tagSuggestionStore.getCount(source, it) }

    suspend fun fetchTagVideoCounts(source: SourceKey, tags: List<String>): Map<String, Int?> =
        tagCounts.fetch(source, tags)

    suspend fun fetchSearchTermCounts(source: SourceKey, terms: List<SearchTerm>): Map<SearchTerm, Int?> =
        searchTermCounts.fetch(source, terms)

    suspend fun resolveNhentaiGalleryById(galleryId: String): Post? {
        val id = galleryId.trim().takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        return registry.adapterFor(SourceKey.NHENTAI)
            ?.resolvePost(PostId(SourceKey.NHENTAI, id))
    }

    suspend fun resolvePostForSearch(postId: PostId, executionKey: String): Post? {
        resolvedPostsByExecution[executionKey]?.get(postId)?.let { return it }
        if (shouldDeferResolve(postId, executionKey)) return null
        val adapter = registry.adapterFor(postId.source) ?: return null
        return try {
            val resolved = postResolutions.resolve(postId) { adapter.resolvePost(postId) } ?: return null
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

    suspend fun restoreSearchScrollState(
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
            pixivSearchToken(raw).takeIf { it.isNotBlank() && seen.add(normalizeMatchToken(it)) }
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

    private fun unifiedFailureMessage(posts: List<Post>, statuses: List<SourceRunStatus>): String =
        if (isPixivUnknownFailure(posts, statuses)) PIXIV_UNKNOWN_RETRY_MESSAGE else
            sourceFailureMessage(statuses, fallback = "These search options are not supported by the selected sources.")

    private fun isPixivUnknownFailure(posts: List<Post>, statuses: List<SourceRunStatus>): Boolean =
        posts.isEmpty() && statuses.any { status ->
            status.source == SourceKey.PIXIV && status.state == SourceRunState.FAILED &&
                (status.failureReason == SourceFailureReason.UNKNOWN ||
                    status.errorMessage?.contains("PIXIV_UNKNOWN", ignoreCase = true) == true)
        }

    private fun isPixivUnknownError(error: Throwable, query: Query): Boolean {
        if (error is SourceAdapterException && error.reason == SourceFailureReason.UNKNOWN) {
            return query.mode == QueryMode.Source(SourceKey.PIXIV)
        }
        return error.message.orEmpty().contains("PIXIV_UNKNOWN", ignoreCase = true)
    }

    private fun isModeAvailable(mode: QueryMode): Boolean = when (mode) {
        QueryMode.Unified -> true
        is QueryMode.Source -> mode.source in registry.availableSources()
    }
}
