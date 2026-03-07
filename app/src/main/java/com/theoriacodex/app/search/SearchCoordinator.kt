package com.theoriacodex.app.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.adapter.TagCountLookupSourceAdapter
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SearchCoordinator(
    private val registry: SourceAdapterRegistry,
    private val queryRepository: QueryRepository = InMemoryQueryRepository(),
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    private val uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
    private val tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var runtimeSettings: AppSettings = AppSettings()
    private var hasExecutedSearch = false
    private val appliedByMode = mutableMapOf<String, Query>()
    private var unifiedNextPageTokens: Map<SourceKey, String?> = emptyMap()
    private var unifiedQueryOverrides: Map<SourceKey, Query> = emptyMap()
    private var sourceNextPageToken: String? = null
    private val lastTrendingRefreshAtBySource = mutableMapOf<SourceKey, Long>()

    var draftQuery by mutableStateOf(defaultQuery())
        private set

    var appliedQuery by mutableStateOf(defaultQuery())
        private set

    var results by mutableStateOf<List<Post>>(emptyList())
        private set

    var statuses by mutableStateOf<List<SourceRunStatus>>(emptyList())
        private set

    var trendingTags by mutableStateOf<List<TagSuggestion>>(emptyList())
        private set

    var autocompleteSuggestions by mutableStateOf<List<TagSuggestion>>(emptyList())
        private set

    var tagInputValidationMessage by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var canLoadMore by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val availableSources: List<SourceKey>
        get() = registry.availableSources().toList().sortedWith(
            compareBy<SourceKey> { source -> sourceDisplayOrderIndex(source) }
                .thenBy { source -> source.name }
        )

    val modeOptions: List<QueryMode>
        get() = listOf(QueryMode.Unified) + availableSources.map(QueryMode::Source)

    val hasPendingChanges: Boolean
        get() = draftQuery != appliedQuery

    val hasAnySearchRun: Boolean
        get() = hasExecutedSearch

    val enabledSourceCount: Int
        get() = effectiveEnabledSources().size

    val appliedQueryHash: String
        get() = QueryHash.from(appliedQuery)

    fun tagVideoCount(source: SourceKey, tag: String): Int? {
        val normalized = tag.trim().lowercase()
        if (normalized.isBlank()) return null

        val cachedCount = tagSuggestionStore
            .get(source = source, limit = TAG_LOOKUP_LIMIT)
            .firstOrNull { suggestion ->
                tagsMatchForSource(source, suggestion.text, normalized)
            }
            ?.count
        if (cachedCount != null) return cachedCount

        val autocompleteCount = autocompleteSuggestions
            .firstOrNull { suggestion ->
                tagsMatchForSource(source, suggestion.text, normalized)
            }
            ?.count
        if (autocompleteCount != null) return autocompleteCount

        return trendingTags
            .firstOrNull { suggestion ->
                tagsMatchForSource(source, suggestion.text, normalized)
            }
            ?.count
    }

    suspend fun fetchTagVideoCount(source: SourceKey, tag: String): Int? {
        val normalized = tag.trim()
        if (normalized.isBlank()) return null
        return fetchTagVideoCounts(source, tags = listOf(normalized))[normalized]
    }

    suspend fun fetchTagVideoCounts(source: SourceKey, tags: List<String>): Map<String, Int?> {
        val requested = tags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (requested.isEmpty()) return emptyMap()

        val resolved = requested.associateWith { tag -> tagVideoCount(source, tag) }.toMutableMap()
        var missing = requested.filter { tag -> resolved[tag] == null }
        if (missing.isEmpty()) return resolved

        val adapter = registry.adapterFor(source) ?: return resolved
        if (adapter is TagCountLookupSourceAdapter) {
            val sourceTags = missing.map { tag -> autocompletePrefixForSource(source, tag) }
            val batchCounts = runCatching {
                adapter.fetchTagCounts(sourceTags)
            }.getOrDefault(emptyMap())
            if (batchCounts.isNotEmpty()) {
                tagSuggestionStore.put(
                    source = source,
                    suggestions = batchCounts.map { (tagText, count) ->
                        TagSuggestion(
                            text = tagText,
                            type = "tag_count_lookup",
                            count = count,
                        )
                    },
                )
                missing.forEach { tag ->
                    val matched = batchCounts.entries
                        .firstOrNull { (name, _) -> tagsMatchForSource(source, name, tag) }
                        ?.value
                    if (matched != null) {
                        resolved[tag] = matched
                    }
                }
            }
            missing = requested.filter { tag -> resolved[tag] == null }
        }

        missing.forEach { tag ->
            val sourcePrefix = autocompletePrefixForSource(source, tag)
            val fetched = runCatching {
                adapter.autocompleteTags(prefix = sourcePrefix, limit = TAG_FETCH_LIMIT)
            }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                tagSuggestionStore.put(source, fetched)
            }
            val count = fetched
                .firstOrNull { suggestion -> tagsMatchForSource(source, suggestion.text, tag) }
                ?.count
                ?: tagVideoCount(source, tag)
            if (count != null) {
                resolved[tag] = count
            }
        }

        return resolved
    }

    suspend fun initialize() {
        runtimeSettings = settingsRepository.observeSettings().first()

        modeOptions.forEach { mode ->
            val stored = queryRepository.observeAppliedQuery(modeKey(mode)).first()
            if (stored != null && isModeAvailable(stored.mode)) {
                appliedByMode[modeKey(mode)] = stored
            }
        }

        val lastApplied = queryRepository.observeAppliedQuery(LAST_ACTIVE_QUERY_KEY).first()
            ?.takeIf { query -> isModeAvailable(query.mode) }
        val restored = if (lastApplied != null) {
            appliedByMode[modeKey(lastApplied.mode)] ?: defaultQuery(lastApplied.mode)
        } else {
            appliedByMode[modeKey(QueryMode.Unified)] ?: defaultQuery()
        }
        appliedQuery = restored
        draftQuery = restored
        hasExecutedSearch =
            appliedByMode.containsKey(modeKey(appliedQuery.mode)) ||
            queryRepository.getScrollOffset(appliedQueryHash) != null
    }

    fun onSettingsChanged(settings: AppSettings): Boolean {
        val previousRuntime = runtimeSettings.runtime
        runtimeSettings = settings

        if (!isModeAvailable(draftQuery.mode)) {
            draftQuery = defaultQuery(QueryMode.Unified)
        }
        if (!isModeAvailable(appliedQuery.mode)) {
            appliedQuery = defaultQuery(QueryMode.Unified)
        }

        return hasExecutedSearch && previousRuntime != settings.runtime
    }

    fun addTagInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        if (trimmed.startsWith("-")) {
            val tag = trimmed.removePrefix("-").trim()
            addExcludeTag(tag)
            return
        }

        addIncludeTag(trimmed)
    }

    fun canCommitTagInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return false
        if (!requiresGelbooruSuggestionSelection()) return true
        val normalizedTag = normalizeTypedTag(trimmed)
        if (normalizedTag.isBlank()) return false
        return isSuggestedTag(normalizedTag)
    }

    fun commitTagInput(input: String): Boolean {
        if (!canCommitTagInput(input)) {
            if (requiresGelbooruSuggestionSelection()) {
                tagInputValidationMessage = GELBOORU_SUGGESTION_REQUIRED_MESSAGE
            }
            return false
        }
        addTagInput(resolveCommittedTagInput(input))
        tagInputValidationMessage = null
        return true
    }

    fun clearTagInputValidationMessage() {
        tagInputValidationMessage = null
    }

    fun clearAutocompleteSuggestions() {
        autocompleteSuggestions = emptyList()
    }

    fun addIncludeTag(tag: String) {
        val normalized = tag.trim()
        if (normalized.isBlank()) return
        if (normalized in draftQuery.includeTags) return
        draftQuery = draftQuery.copy(includeTags = draftQuery.includeTags + normalized)
    }

    fun addExcludeTag(tag: String) {
        val normalized = tag.trim()
        if (normalized.isBlank()) return
        if (normalized in draftQuery.excludeTags) return
        draftQuery = draftQuery.copy(excludeTags = draftQuery.excludeTags + normalized)
    }

    fun removeIncludeTag(tag: String) {
        draftQuery = draftQuery.copy(includeTags = draftQuery.includeTags.filterNot { it == tag })
    }

    fun removeExcludeTag(tag: String) {
        draftQuery = draftQuery.copy(excludeTags = draftQuery.excludeTags.filterNot { it == tag })
    }

    fun setMode(mode: QueryMode) {
        val resolvedMode = when {
            isModeAvailable(mode) -> mode
            else -> QueryMode.Unified
        }
        val restored = appliedByMode[modeKey(resolvedMode)] ?: defaultQuery(resolvedMode)
        draftQuery = restored.copy(mode = resolvedMode)
        clearTagInputUiState()
    }

    fun setSort(sort: SortMode) {
        draftQuery = draftQuery.copy(sort = sort)
    }

    fun resetDraft() {
        draftQuery = appliedQuery
        clearTagInputUiState()
    }

    fun clearDraft() {
        val mode = draftQuery.mode.takeIf(::isModeAvailable) ?: QueryMode.Unified
        draftQuery = defaultQuery(mode)
        clearTagInputUiState()
    }

    fun applyQuickQuery(kind: QuickQueryKind) {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L

        val sort = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> SortMode.POPULAR
            QuickQueryKind.TOP_7D -> SortMode.TOP
            QuickQueryKind.TOP_30D -> SortMode.TOP
            QuickQueryKind.NEWEST -> SortMode.NEWEST
            QuickQueryKind.RANDOM -> SortMode.RANDOM
        }
        val dateRange = when (kind) {
            QuickQueryKind.POPULAR_TODAY -> DateRange(fromEpochMs = now - dayMs, toEpochMs = now)
            QuickQueryKind.TOP_7D -> DateRange(fromEpochMs = now - 7L * dayMs, toEpochMs = now)
            QuickQueryKind.TOP_30D -> DateRange(fromEpochMs = now - 30L * dayMs, toEpochMs = now)
            QuickQueryKind.NEWEST, QuickQueryKind.RANDOM -> null
        }
        draftQuery = defaultQuery(QueryMode.Unified).copy(
            sort = sort,
            dateRange = dateRange,
        )
        clearTagInputUiState()
    }

    fun addTrendingTag(tag: String) {
        addIncludeTag(tag)
    }

    fun prepareExploreTagSearch(
        includeTags: List<String>,
        excludeTags: List<String> = emptyList(),
    ): Boolean {
        val normalizedInclude = includeTags
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val normalizedExclude = excludeTags
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it in normalizedInclude }
            .distinct()
        if (normalizedInclude.isEmpty() && normalizedExclude.isEmpty()) return false
        draftQuery = defaultQuery(QueryMode.Unified).copy(
            includeTags = normalizedInclude,
            excludeTags = normalizedExclude,
        )
        clearSearchResultsForRetry()
        statuses = emptyList()
        errorMessage = null
        clearTagInputUiState()
        return true
    }

    fun prepareExploreTagSearch(tag: String): Boolean {
        return prepareExploreTagSearch(includeTags = listOf(tag))
    }

    fun setDateRangePreset(preset: DateRangePreset) {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val dateRange = when (preset) {
            DateRangePreset.NONE -> null
            DateRangePreset.TODAY -> DateRange(fromEpochMs = now - dayMs, toEpochMs = now)
            DateRangePreset.LAST_7_DAYS -> DateRange(fromEpochMs = now - 7L * dayMs, toEpochMs = now)
            DateRangePreset.LAST_30_DAYS -> DateRange(fromEpochMs = now - 30L * dayMs, toEpochMs = now)
        }
        draftQuery = draftQuery.copy(dateRange = dateRange)
    }

    fun setMinScore(minScore: Int?) {
        draftQuery = draftQuery.copy(minScore = minScore)
    }

    fun selectedNhentaiLanguageFilter(): NhentaiLanguageFilter {
        val match = draftQuery.includeTags.firstNotNullOfOrNull { tag ->
            nhentaiLanguageFilterForTag(tag)
        }
        return match ?: NhentaiLanguageFilter.ANY
    }

    fun setNhentaiLanguageFilter(filter: NhentaiLanguageFilter) {
        val cleaned = draftQuery.includeTags.filterNot { tag ->
            nhentaiLanguageFilterForTag(tag) != null
        }
        val languageTag = NHENTAI_LANGUAGE_TAG_BY_FILTER[filter]
        val nextInclude = if (languageTag == null || languageTag in cleaned) {
            cleaned
        } else {
            cleaned + languageTag
        }
        draftQuery = draftQuery.copy(includeTags = nextInclude)
    }

    fun directNhentaiGalleryIdCandidate(query: Query = draftQuery): String? {
        return query.directNhentaiGalleryIdCandidate()
    }

    suspend fun resolveNhentaiGalleryById(galleryId: String): Post? {
        val normalizedId = galleryId.trim().takeIf(String::isDigitsOnly) ?: return null
        val adapter = registry.adapterFor(SourceKey.NHENTAI) ?: return null
        return adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = normalizedId))
    }

    fun resetFilters() {
        draftQuery = draftQuery.copy(
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    suspend fun refreshAutocompleteSuggestions(input: String) {
        val typedPrefix = normalizeTypedTag(input)
        if (typedPrefix.isBlank()) {
            autocompleteSuggestions = emptyList()
            return
        }

        autocompleteSuggestions = when (val mode = draftQuery.mode) {
            QueryMode.Unified -> {
                val enabledSources = effectiveEnabledSources()
                val fetched = enabledSources
                    .flatMap { source ->
                        val sourcePrefix = autocompletePrefixForSource(source, typedPrefix)
                        val suggestions = runCatching {
                            registry.adapterFor(source)?.autocompleteTags(prefix = sourcePrefix, limit = 10).orEmpty()
                        }.getOrDefault(emptyList())
                        if (suggestions.isNotEmpty()) {
                            tagSuggestionStore.put(source, suggestions)
                        }
                        suggestions
                    }
                if (fetched.isNotEmpty()) {
                    rankSuggestionsByPrefix(fetched, prefix = typedPrefix, limit = 20)
                } else {
                    val cached = enabledSources
                        .flatMap { source -> tagSuggestionStore.get(source, limit = 120) }
                    rankSuggestionsByPrefix(cached, prefix = typedPrefix, limit = 20)
                }
            }

            is QueryMode.Source -> {
                val sourcePrefix = autocompletePrefixForSource(mode.source, typedPrefix)
                val fetched = runCatching {
                    registry.adapterFor(mode.source)?.autocompleteTags(prefix = sourcePrefix, limit = 20).orEmpty()
                }.getOrDefault(emptyList())
                if (fetched.isNotEmpty()) {
                    tagSuggestionStore.put(mode.source, fetched)
                    rankSuggestionsByPrefix(fetched, prefix = typedPrefix, limit = 20)
                } else {
                    val cached = tagSuggestionStore.get(mode.source, limit = 120)
                    val fallback = if (cached.isNotEmpty()) cached else trendingTags
                    rankSuggestionsByPrefix(fallback, prefix = typedPrefix, limit = 20)
                }
            }
        }
    }

    suspend fun loadTrendingTags(forceRefresh: Boolean = false) {
        errorMessage = null
        val now = clock()
        when (val mode = draftQuery.mode) {
            QueryMode.Unified -> {
                val enabled = effectiveEnabledSources()
                if (enabled.isEmpty()) {
                    trendingTags = emptyList()
                    return
                }

                val cachedBySource = enabled.associateWith { source ->
                    tagSuggestionStore.get(source, limit = TRENDING_PER_SOURCE_CACHE_LIMIT)
                }
                trendingTags = rankTrendingSuggestions(
                    suggestions = cachedBySource.values.flatten(),
                    limit = UNIFIED_TRENDING_LIMIT,
                )

                val sourcesToRefresh = enabled.filter { source ->
                    shouldRefreshTrending(
                        source = source,
                        nowEpochMs = now,
                        forceRefresh = forceRefresh,
                        cached = cachedBySource[source].orEmpty(),
                    )
                }
                if (sourcesToRefresh.isEmpty()) {
                    return
                }

                var refreshedAny = false
                sourcesToRefresh.forEach { source ->
                    val fetched = fetchTrendingForSource(source = source, limit = TRENDING_FETCH_PER_SOURCE_LIMIT)
                    if (fetched.isNotEmpty()) {
                        refreshedAny = true
                    }
                }
                if (refreshedAny || trendingTags.isEmpty()) {
                    val refreshed = enabled.flatMap { source ->
                        tagSuggestionStore.get(source, limit = TRENDING_PER_SOURCE_CACHE_LIMIT)
                    }
                    trendingTags = rankTrendingSuggestions(
                        suggestions = refreshed,
                        limit = UNIFIED_TRENDING_LIMIT,
                    )
                }
            }

            is QueryMode.Source -> {
                val source = mode.source
                val cached = tagSuggestionStore.get(source, limit = SOURCE_TRENDING_LIMIT)
                trendingTags = rankTrendingSuggestions(cached, limit = SOURCE_TRENDING_LIMIT)

                if (!shouldRefreshTrending(source, now, forceRefresh, cached)) {
                    return
                }

                val fetched = fetchTrendingForSource(source = source, limit = SOURCE_TRENDING_LIMIT)
                if (fetched.isNotEmpty() || trendingTags.isEmpty()) {
                    val refreshed = tagSuggestionStore.get(source, limit = SOURCE_TRENDING_LIMIT)
                    trendingTags = rankTrendingSuggestions(refreshed, limit = SOURCE_TRENDING_LIMIT)
                }
            }
        }
    }

    private fun shouldRefreshTrending(
        source: SourceKey,
        nowEpochMs: Long,
        forceRefresh: Boolean,
        cached: List<TagSuggestion>,
    ): Boolean {
        if (forceRefresh) return true
        if (cached.isEmpty()) return true
        val lastRefresh = lastTrendingRefreshAtBySource[source] ?: return true
        return nowEpochMs - lastRefresh >= TRENDING_REFRESH_INTERVAL_MS
    }

    private suspend fun fetchTrendingForSource(source: SourceKey, limit: Int): List<TagSuggestion> {
        val fetched = runCatching {
            registry.adapterFor(source)?.trendingTags(limit = limit).orEmpty()
        }.getOrDefault(emptyList())
        lastTrendingRefreshAtBySource[source] = clock()
        if (fetched.isNotEmpty()) {
            tagSuggestionStore.put(source, fetched)
        }
        return fetched
    }

    private fun rankTrendingSuggestions(
        suggestions: List<TagSuggestion>,
        limit: Int,
    ): List<TagSuggestion> {
        if (limit <= 0) return emptyList()
        return suggestions
            .asSequence()
            .filter { suggestion -> suggestion.text.isNotBlank() }
            .distinctBy { suggestion -> normalizeMatchToken(suggestion.text) }
            .sortedWith(
                compareByDescending<TagSuggestion> { suggestion -> suggestion.count ?: Int.MIN_VALUE }
                    .thenBy { suggestion -> suggestion.text.lowercase() }
            )
            .take(limit)
            .toList()
    }

    suspend fun applyDraft() {
        appliedQuery = draftQuery
        appliedByMode[modeKey(appliedQuery.mode)] = appliedQuery
        queryRepository.upsertAppliedQuery(modeKey(appliedQuery.mode), appliedQuery)
        queryRepository.upsertAppliedQuery(LAST_ACTIVE_QUERY_KEY, appliedQuery)
        val hash = appliedQueryHash
        uiRestoreRepository.setSearchScrollState(
            queryHash = hash,
            state = SearchScrollState(
                firstVisibleItemIndex = 0,
                firstVisibleItemOffsetPx = 0,
            ),
        )
        queryRepository.upsertScrollOffset(hash, 0)
        executeSearch()
    }

    suspend fun retry() {
        executeSearch()
    }

    suspend fun restoreLastAppliedSearchIfNeeded() {
        if (!hasExecutedSearch) return
        if (hasPendingChanges) return
        if (loading || loadingMore || results.isNotEmpty()) return
        executeSearch()
    }

    suspend fun loadNextPage() {
        if (loading || loadingMore || !canLoadMore) return

        loadingMore = true
        errorMessage = null
        try {
            val enabledSources = effectiveEnabledSources()
            when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> {
                    val disabledStatuses = availableSources
                        .filterNot { it in enabledSources }
                        .map { source ->
                            SourceRunStatus(
                                source = source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Disabled in settings",
                            )
                        }
                    if (enabledSources.isEmpty()) {
                        canLoadMore = false
                        statuses = disabledStatuses
                        return
                    }

                    val pageableSources = enabledSources.filterTo(mutableSetOf()) { source ->
                        !unifiedNextPageTokens[source].isNullOrBlank()
                    }
                    if (pageableSources.isEmpty()) {
                        canLoadMore = false
                        return
                    }

                    val result = registry.unifiedOrchestrator().search(
                        query = appliedQuery,
                        enabledSources = pageableSources,
                        pageTokens = unifiedNextPageTokens.filterKeys { it in pageableSources },
                        weights = effectiveWeights(pageableSources),
                        queryOverridesBySource = unifiedQueryOverrides.filterKeys { it in pageableSources },
                    )
                    results = mergeResults(results, result.items)
                    rememberSeenTags(result.items)
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                    unifiedNextPageTokens = unifiedNextPageTokens.toMutableMap().apply {
                        putAll(result.nextPageTokens)
                    }
                    canLoadMore = unifiedNextPageTokens.values.any { !it.isNullOrBlank() }
                    maybeHandlePixivUnknownFailure()
                }

                is QueryMode.Source -> {
                    val token = sourceNextPageToken
                    if (token.isNullOrBlank()) {
                        canLoadMore = false
                        return
                    }
                    val adapter = requireNotNull(registry.adapterFor(mode.source)) {
                        "No adapter for ${mode.source}"
                    }
                    val page = adapter.search(appliedQuery, pageToken = token)
                    results = mergeResults(results, page.items)
                    rememberSeenTags(page.items)
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                    sourceNextPageToken = page.nextPageToken
                    canLoadMore = !sourceNextPageToken.isNullOrBlank()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = if (isPixivUnknownError(error)) {
                PIXIV_UNKNOWN_RETRY_MESSAGE
            } else {
                error.message ?: "Could not load more results"
            }
            canLoadMore = false
        } finally {
            loadingMore = false
        }
    }

    suspend fun persistSearchScrollState(index: Int, offsetPx: Int) {
        val hash = appliedQueryHash
        uiRestoreRepository.setSearchScrollState(
            queryHash = hash,
            state = SearchScrollState(
                firstVisibleItemIndex = index,
                firstVisibleItemOffsetPx = offsetPx,
            ),
        )
        queryRepository.upsertScrollOffset(hash, offsetPx)
    }

    suspend fun restoreSearchScrollState(): SearchScrollState? {
        val hash = appliedQueryHash
        return uiRestoreRepository.getSearchScrollState(hash)
            ?: queryRepository.getScrollOffset(hash)?.let { offset ->
                SearchScrollState(firstVisibleItemIndex = 0, firstVisibleItemOffsetPx = offset)
            }
    }

    fun buildViewerLaunchContext(
        startIndex: Int,
        scrollOffsetHint: Int,
    ): ViewerLaunchContext {
        return ViewerLaunchContext(
            queryHash = appliedQueryHash,
            startIndex = startIndex,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = scrollOffsetHint,
        )
    }

    suspend fun setViewerLaunchContext(context: ViewerLaunchContext?) {
        uiRestoreRepository.setViewerLaunchContext(context)
    }

    private suspend fun executeSearch() {
        val previousResults = results
        val previousStatuses = statuses
        loading = true
        loadingMore = false
        canLoadMore = false
        unifiedNextPageTokens = emptyMap()
        unifiedQueryOverrides = emptyMap()
        sourceNextPageToken = null
        errorMessage = null
        statuses = emptyList()

        try {
            val enabledSources = effectiveEnabledSources()
            when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> {
                    val disabledStatuses = availableSources
                        .filterNot { it in enabledSources }
                        .map { source ->
                            SourceRunStatus(
                                source = source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Disabled in settings",
                            )
                        }
                    if (enabledSources.isEmpty()) {
                        results = emptyList()
                        statuses = disabledStatuses
                        return
                    }

                    unifiedQueryOverrides = buildUnifiedQueryOverrides(
                        query = appliedQuery,
                        enabledSources = enabledSources,
                    )
                    val result = registry.unifiedOrchestrator().search(
                        query = appliedQuery,
                        enabledSources = enabledSources,
                        pageTokens = emptyMap(),
                        weights = effectiveWeights(enabledSources),
                        queryOverridesBySource = unifiedQueryOverrides,
                    )
                    results = result.items
                    rememberSeenTags(result.items)
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                    unifiedNextPageTokens = result.nextPageTokens
                    canLoadMore = unifiedNextPageTokens.values.any { !it.isNullOrBlank() }
                    maybeHandlePixivUnknownFailure()
                }

                is QueryMode.Source -> {
                    if (!isModeAvailable(mode)) {
                        results = emptyList()
                        statuses = listOf(
                            SourceRunStatus(
                                source = mode.source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Source not available in this build",
                            )
                        )
                        return
                    }
                    if (mode.source !in enabledSources) {
                        results = emptyList()
                        statuses = listOf(
                            SourceRunStatus(
                                source = mode.source,
                                state = SourceRunState.EXCLUDED,
                                errorMessage = "Disabled in settings",
                            )
                        )
                        return
                    }
                    val adapter = requireNotNull(registry.adapterFor(mode.source)) {
                        "No adapter for ${mode.source}"
                    }
                    val page = adapter.search(appliedQuery, pageToken = null)
                    results = page.items
                    rememberSeenTags(page.items)
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                    sourceNextPageToken = page.nextPageToken
                    canLoadMore = !sourceNextPageToken.isNullOrBlank()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isPixivUnknownError(error)) {
                clearSearchResultsForRetry()
                errorMessage = PIXIV_UNKNOWN_RETRY_MESSAGE
            } else {
                results = previousResults
                statuses = previousStatuses
                errorMessage = error.message ?: "Unknown error"
                canLoadMore = false
            }
        } finally {
            hasExecutedSearch = true
            loading = false
        }
    }

    private fun maybeHandlePixivUnknownFailure() {
        if (results.isNotEmpty()) return
        val hasPixivUnknownFailure = statuses.any { status ->
            status.source == SourceKey.PIXIV &&
                status.state == SourceRunState.FAILED &&
                (
                    status.failureReason == SourceFailureReason.UNKNOWN ||
                        status.errorMessage?.contains("PIXIV_UNKNOWN", ignoreCase = true) == true
                    )
        }
        if (hasPixivUnknownFailure) {
            clearSearchResultsForRetry()
            errorMessage = PIXIV_UNKNOWN_RETRY_MESSAGE
        }
    }

    private fun clearSearchResultsForRetry() {
        results = emptyList()
        canLoadMore = false
        unifiedNextPageTokens = emptyMap()
        unifiedQueryOverrides = emptyMap()
        sourceNextPageToken = null
    }

    private suspend fun buildUnifiedQueryOverrides(
        query: Query,
        enabledSources: Set<SourceKey>,
    ): Map<SourceKey, Query> {
        if (query.mode != QueryMode.Unified) return emptyMap()
        val overrides = mutableMapOf<SourceKey, Query>()

        if (SourceKey.GELBOORU in enabledSources) {
            val gelbooruAdapter = registry.adapterFor(SourceKey.GELBOORU)
            if (gelbooruAdapter != null) {
                val includeTags = resolveGelbooruCompatibilityTags(gelbooruAdapter, query.includeTags)
                val excludeTags = resolveGelbooruCompatibilityTags(gelbooruAdapter, query.excludeTags)
                overrides[SourceKey.GELBOORU] = query.copy(
                    includeTags = includeTags,
                    excludeTags = excludeTags,
                )
            }
        }

        if (SourceKey.PIXIV in enabledSources) {
            overrides[SourceKey.PIXIV] = query.copy(
                includeTags = resolvePixivCompatibilityTags(query.includeTags),
                excludeTags = resolvePixivCompatibilityTags(query.excludeTags),
            )
        }

        return overrides
    }

    private suspend fun resolveGelbooruCompatibilityTags(
        adapter: SourceAdapter,
        tags: List<String>,
    ): List<String> {
        if (tags.isEmpty()) return emptyList()

        val cache = mutableMapOf<String, String>()
        val resolved = mutableListOf<String>()
        tags.forEach { raw ->
            val normalized = raw.trim()
            if (normalized.isBlank()) return@forEach
            val key = normalized.lowercase()
            val mapped = cache.getOrPut(key) {
                val sourcePrefix = autocompletePrefixForSource(SourceKey.GELBOORU, normalized)
                val suggestions = runCatching {
                    adapter.autocompleteTags(prefix = sourcePrefix, limit = 1)
                }.getOrDefault(emptyList())
                if (suggestions.isNotEmpty()) {
                    tagSuggestionStore.put(SourceKey.GELBOORU, suggestions)
                }
                suggestions.firstOrNull()?.text?.trim().takeUnless { it.isNullOrBlank() } ?: normalized
            }
            if (mapped !in resolved) {
                resolved += mapped
            }
        }
        return resolved
    }

    private fun resolvePixivCompatibilityTags(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()

        val resolved = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        tags.forEach { raw ->
            val mapped = normalizePixivCompatibilityToken(raw)
            if (mapped.isBlank()) return@forEach

            val dedupeKey = normalizeMatchToken(mapped)
            if (seen.add(dedupeKey)) {
                resolved += mapped
            }
        }
        return resolved
    }

    private fun normalizePixivCompatibilityToken(raw: String): String {
        var normalized = raw
            .trim()
            .removePrefix("-")
            .replace('_', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        while (normalized.isNotBlank() && PIXIV_TRAILING_PARENTHESIS_REGEX.containsMatchIn(normalized)) {
            normalized = normalized.replace(PIXIV_TRAILING_PARENTHESIS_REGEX, "").trim()
        }
        return normalized
    }

    private fun isPixivUnknownError(error: Throwable): Boolean {
        if (error is SourceAdapterException && error.reason == SourceFailureReason.UNKNOWN) {
            return when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> true
                is QueryMode.Source -> mode.source == SourceKey.PIXIV
            }
        }
        return error.message
            .orEmpty()
            .contains("PIXIV_UNKNOWN", ignoreCase = true)
    }

    private fun mergeResults(
        current: List<Post>,
        next: List<Post>,
    ): List<Post> {
        if (next.isEmpty()) return current
        if (current.isEmpty()) return next

        val seen = current
            .mapTo(mutableSetOf()) { "${it.id.source.name}:${it.id.sourcePostId}" }
        val merged = current.toMutableList()
        next.forEach { post ->
            val key = "${post.id.source.name}:${post.id.sourcePostId}"
            if (seen.add(key)) {
                merged += post
            }
        }
        return merged
    }

    private fun rememberSeenTags(posts: List<Post>) {
        if (posts.isEmpty()) return

        posts
            .groupBy { post -> post.id.source }
            .forEach { (source, sourcePosts) ->
                val seenSuggestions = sourcePosts
                    .asSequence()
                    .flatMap { post ->
                        val tags = post.rawTags.ifEmpty { post.canonicalTags }
                        tags.asSequence()
                    }
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map { tag -> TagSuggestion(text = normalizeStoredTagForSource(source, tag), type = "seen", count = null) }
                    .distinctBy { suggestion -> sourceTagKey(source, suggestion.text) }
                    .take(SEEN_TAGS_PER_SOURCE_INGEST_LIMIT)
                    .toList()
                if (seenSuggestions.isNotEmpty()) {
                    tagSuggestionStore.put(source, seenSuggestions)
                }
            }
    }

    private fun modeKey(mode: QueryMode): String {
        return when (mode) {
            QueryMode.Unified -> "unified"
            is QueryMode.Source -> "source:${mode.source.name}"
        }
    }

    private fun isModeAvailable(mode: QueryMode): Boolean {
        return when (mode) {
            QueryMode.Unified -> true
            is QueryMode.Source -> mode.source in registry.availableSources()
        }
    }

    private fun effectiveEnabledSources(): Set<SourceKey> {
        return runtimeSettings.runtime.enabledSources.intersect(registry.availableSources())
    }

    private fun requiresGelbooruSuggestionSelection(): Boolean {
        return draftQuery.mode == QueryMode.Source(SourceKey.GELBOORU)
    }

    private fun normalizeTypedTag(input: String): String {
        return input.trim().removePrefix("-").trim()
    }

    private fun autocompletePrefixForSource(source: SourceKey, input: String): String {
        val normalized = normalizeTypedTag(input)
        return when (source) {
            SourceKey.GELBOORU, SourceKey.RULE34XXX -> normalizeGelbooruToken(normalized)
            else -> normalized
        }
    }

    private fun resolveCommittedTagInput(input: String): String {
        val trimmed = input.trim()
        val source = (draftQuery.mode as? QueryMode.Source)?.source ?: return trimmed
        if (source !in SUGGESTION_CANONICALIZATION_SOURCES) return trimmed

        val isExclude = trimmed.startsWith("-")
        val typedTag = normalizeTypedTag(trimmed)
        val matched = autocompleteSuggestions
            .firstOrNull { suggestion -> tagsMatchForSource(source, suggestion.text, typedTag) }
            ?.text
            ?.trim()
            .orEmpty()
            .ifBlank { typedTag }
        return if (isExclude) "-$matched" else matched
    }

    private fun tagsMatchForSource(source: SourceKey, suggestionText: String, typedTag: String): Boolean {
        val left = suggestionText.trim()
        val right = typedTag.trim()
        if (left.isBlank() || right.isBlank()) return false
        return when (source) {
            SourceKey.GELBOORU, SourceKey.RULE34XXX ->
                normalizeGelbooruToken(left) == normalizeGelbooruToken(right)
            SourceKey.PIXIV,
            SourceKey.NHENTAI,
            SourceKey.RULE34PAHEAL,
            SourceKey.RULE34VIDEO,
            SourceKey.RULE34GEN,
            -> normalizeMatchToken(left) == normalizeMatchToken(right)
            else -> left.equals(right, ignoreCase = true)
        }
    }

    private fun normalizeGelbooruToken(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(WHITESPACE_REGEX, "_")
    }

    private fun normalizeStoredTagForSource(source: SourceKey, value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        return when (source) {
            SourceKey.GELBOORU, SourceKey.RULE34XXX -> normalizeGelbooruToken(normalized)
            else -> normalized
        }
    }

    private fun sourceTagKey(source: SourceKey, tag: String): String {
        return when (source) {
            SourceKey.GELBOORU, SourceKey.RULE34XXX -> normalizeGelbooruToken(tag)
            SourceKey.PIXIV,
            SourceKey.NHENTAI,
            SourceKey.RULE34PAHEAL,
            SourceKey.RULE34VIDEO,
            SourceKey.RULE34GEN,
            -> normalizeMatchToken(tag)
            else -> tag.trim().lowercase()
        }
    }

    private fun isSuggestedTag(tag: String): Boolean {
        return autocompleteSuggestions.any { suggestion ->
            tagsMatchForSource(SourceKey.GELBOORU, suggestion.text, tag)
        }
    }

    private fun clearTagInputUiState() {
        autocompleteSuggestions = emptyList()
        tagInputValidationMessage = null
    }

    private fun rankSuggestionsByPrefix(
        suggestions: List<TagSuggestion>,
        prefix: String,
        limit: Int,
    ): List<TagSuggestion> {
        val normalizedPrefix = normalizeMatchToken(prefix)
        if (normalizedPrefix.isBlank() || limit <= 0) return emptyList()
        return suggestions
            .asSequence()
            .filter { suggestion ->
                normalizeMatchToken(suggestion.text).contains(normalizedPrefix)
            }
            .distinctBy { suggestion -> suggestion.text.trim().lowercase() }
            .sortedWith(
                compareByDescending<TagSuggestion> { suggestion ->
                    suggestion.count ?: Int.MIN_VALUE
                }.thenBy { suggestion ->
                    !normalizeMatchToken(suggestion.text).startsWith(normalizedPrefix)
                }.thenBy { suggestion -> suggestion.text.lowercase() }
            )
            .take(limit)
            .toList()
    }

    private fun normalizeMatchToken(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace('_', ' ')
            .replace(WHITESPACE_REGEX, " ")
    }

    private fun effectiveWeights(enabledSources: Set<SourceKey>): Map<SourceKey, Double> {
        if (enabledSources.isEmpty()) return emptyMap()
        val raw = enabledSources.associateWith { source ->
            runtimeSettings.runtime.sourceWeights[source] ?: 1.0
        }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: enabledSources.size.toDouble()
        return raw.mapValues { (_, weight) -> weight / total }
    }

    private fun defaultQuery(mode: QueryMode = QueryMode.Unified): Query {
        return Query(
            mode = mode,
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private fun sourceDisplayOrderIndex(source: SourceKey): Int {
        return SOURCE_DISPLAY_ORDER.indexOf(source).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
}

enum class DateRangePreset {
    NONE,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}

enum class NhentaiLanguageFilter {
    ANY,
    ENGLISH,
    CHINESE,
    JAPANESE,
}

private const val PIXIV_UNKNOWN_RETRY_MESSAGE =
    "Pixiv returned a temporary unknown error. Search was reset. Please retry."
private const val GELBOORU_SUGGESTION_REQUIRED_MESSAGE =
    "For Gelbooru, pick a suggested tag from autocomplete."
private const val TAG_LOOKUP_LIMIT = 20_000
private const val TAG_FETCH_LIMIT = 25
private const val TRENDING_REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L
private const val TRENDING_FETCH_PER_SOURCE_LIMIT = 10
private const val TRENDING_PER_SOURCE_CACHE_LIMIT = 40
private const val SOURCE_TRENDING_LIMIT = 20
private const val UNIFIED_TRENDING_LIMIT = 20
private const val SEEN_TAGS_PER_SOURCE_INGEST_LIMIT = 240
private const val LAST_ACTIVE_QUERY_KEY = "last_active"
private val SOURCE_DISPLAY_ORDER = listOf(
    SourceKey.GELBOORU,
    SourceKey.PIXIV,
    SourceKey.NHENTAI,
    SourceKey.RULE34XXX,
    SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
    SourceKey.AIBOORU,
)
private val NHENTAI_LANGUAGE_TAG_BY_FILTER = mapOf(
    NhentaiLanguageFilter.ENGLISH to "english",
    NhentaiLanguageFilter.CHINESE to "chinese",
    NhentaiLanguageFilter.JAPANESE to "japanese",
)
private val NHENTAI_LANGUAGE_FILTER_TAGS = NHENTAI_LANGUAGE_TAG_BY_FILTER.values.toSet()
private val SUGGESTION_CANONICALIZATION_SOURCES = setOf(
    SourceKey.PIXIV,
    SourceKey.GELBOORU,
    SourceKey.NHENTAI,
    SourceKey.RULE34XXX,
    SourceKey.RULE34PAHEAL,
    SourceKey.RULE34VIDEO,
    SourceKey.RULE34GEN,
)
private val WHITESPACE_REGEX = Regex("\\s+")
private val PIXIV_TRAILING_PARENTHESIS_REGEX = Regex("\\s*\\([^)]*\\)\\s*$")

private fun nhentaiLanguageFilterForTag(tag: String): NhentaiLanguageFilter? {
    return when (normalizeNhentaiLanguageTag(tag)) {
        "english" -> NhentaiLanguageFilter.ENGLISH
        "chinese" -> NhentaiLanguageFilter.CHINESE
        "japanese" -> NhentaiLanguageFilter.JAPANESE
        else -> null
    }
}

private fun normalizeNhentaiLanguageTag(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace('_', ' ')
        .replace(WHITESPACE_REGEX, " ")
}

private fun Query.directNhentaiGalleryIdCandidate(): String? {
    val supportsDirectLookup = mode == QueryMode.Unified || mode == QueryMode.Source(SourceKey.NHENTAI)
    if (!supportsDirectLookup) return null
    if (excludeTags.isNotEmpty()) return null

    val includes = includeTags
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    val searchable = includes.filterNot { tag ->
        normalizeNhentaiLanguageTag(tag) in NHENTAI_LANGUAGE_FILTER_TAGS
    }
    if (searchable.size != 1) return null

    return searchable.first().takeIf(String::isDigitsOnly)
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { ch -> ch.isDigit() }
}
