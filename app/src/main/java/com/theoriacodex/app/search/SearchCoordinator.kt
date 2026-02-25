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
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
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
) {
    private var runtimeSettings: AppSettings = AppSettings()
    private var hasExecutedSearch = false
    private val appliedByMode = mutableMapOf<String, Query>()
    private var unifiedNextPageTokens: Map<SourceKey, String?> = emptyMap()
    private var sourceNextPageToken: String? = null

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

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var canLoadMore by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val availableSources: List<SourceKey>
        get() = registry.availableSources().toList().sortedBy { it.name }

    val modeOptions: List<QueryMode>
        get() = listOf(QueryMode.Unified) + availableSources.map(QueryMode::Source)

    val hasPendingChanges: Boolean
        get() = draftQuery != appliedQuery

    val enabledSourceCount: Int
        get() = effectiveEnabledSources().size

    val appliedQueryHash: String
        get() = QueryHash.from(appliedQuery)

    suspend fun initialize() {
        runtimeSettings = settingsRepository.observeSettings().first()

        modeOptions.forEach { mode ->
            val stored = queryRepository.observeAppliedQuery(modeKey(mode)).first()
            if (stored != null && isModeAvailable(stored.mode)) {
                appliedByMode[modeKey(mode)] = stored
            }
        }

        val unified = appliedByMode[modeKey(QueryMode.Unified)] ?: defaultQuery()
        appliedQuery = unified
        draftQuery = unified
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
    }

    fun setSort(sort: SortMode) {
        draftQuery = draftQuery.copy(sort = sort)
    }

    fun resetDraft() {
        draftQuery = appliedQuery
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
        draftQuery = draftQuery.copy(sort = sort, dateRange = dateRange)
    }

    fun addTrendingTag(tag: String) {
        addIncludeTag(tag)
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

    fun resetFilters() {
        draftQuery = draftQuery.copy(
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    suspend fun loadTrendingTags() {
        errorMessage = null
        val mode = draftQuery.mode

        trendingTags = when (mode) {
            QueryMode.Unified -> {
                val enabled = effectiveEnabledSources()
                val cached = enabled
                    .flatMap { source -> tagSuggestionStore.get(source, limit = 10) }
                    .distinctBy { it.text }
                    .take(20)
                if (cached.isNotEmpty()) {
                    cached
                } else {
                    enabled
                        .flatMap { source ->
                            val fetched = runCatching {
                                registry.adapterFor(source)?.trendingTags(limit = 10).orEmpty()
                            }.getOrDefault(emptyList())
                            if (fetched.isNotEmpty()) {
                                tagSuggestionStore.put(source, fetched)
                            }
                            fetched
                        }
                        .distinctBy { it.text }
                        .take(20)
                }
            }
            is QueryMode.Source -> {
                val cached = tagSuggestionStore.get(mode.source, limit = 20)
                if (cached.isNotEmpty()) {
                    cached
                } else {
                    val fetched = runCatching {
                        registry.adapterFor(mode.source)?.trendingTags(limit = 20).orEmpty()
                    }.getOrDefault(emptyList())
                    if (fetched.isNotEmpty()) {
                        tagSuggestionStore.put(mode.source, fetched)
                    }
                    fetched
                }
            }
        }
    }

    suspend fun applyDraft() {
        appliedQuery = draftQuery
        appliedByMode[modeKey(appliedQuery.mode)] = appliedQuery
        queryRepository.upsertAppliedQuery(modeKey(appliedQuery.mode), appliedQuery)
        executeSearch()
    }

    suspend fun retry() {
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
                    )
                    results = mergeResults(results, result.items)
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                    unifiedNextPageTokens = unifiedNextPageTokens.toMutableMap().apply {
                        putAll(result.nextPageTokens)
                    }
                    canLoadMore = unifiedNextPageTokens.values.any { !it.isNullOrBlank() }
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
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                    sourceNextPageToken = page.nextPageToken
                    canLoadMore = !sourceNextPageToken.isNullOrBlank()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorMessage = error.message ?: "Could not load more results"
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

                    val result = registry.unifiedOrchestrator().search(
                        query = appliedQuery,
                        enabledSources = enabledSources,
                        pageTokens = emptyMap(),
                        weights = effectiveWeights(enabledSources),
                    )
                    results = result.items
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                    unifiedNextPageTokens = result.nextPageTokens
                    canLoadMore = unifiedNextPageTokens.values.any { !it.isNullOrBlank() }
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
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                    sourceNextPageToken = page.nextPageToken
                    canLoadMore = !sourceNextPageToken.isNullOrBlank()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            results = previousResults
            statuses = previousStatuses
            errorMessage = error.message ?: "Unknown error"
            canLoadMore = false
        } finally {
            hasExecutedSearch = true
            loading = false
        }
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
}

enum class DateRangePreset {
    NONE,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}
