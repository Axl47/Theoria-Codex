package com.theoriacodex.app.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
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
import com.theoriacodex.stubs.StubAdapterRegistry
import com.theoriacodex.stubs.StubScenarioPreset
import kotlinx.coroutines.flow.first

class SearchCoordinator(
    private val registry: StubAdapterRegistry = StubAdapterRegistry(),
    private val queryRepository: QueryRepository = InMemoryQueryRepository(),
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    private val uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
) {
    private var runtimeSettings: AppSettings = AppSettings()
    private var hasExecutedSearch = false
    private val appliedByMode = mutableMapOf<String, Query>()

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

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val hasPendingChanges: Boolean
        get() = draftQuery != appliedQuery

    val enabledSourceCount: Int
        get() = runtimeSettings.runtime.enabledSources.size

    val appliedQueryHash: String
        get() = QueryHash.from(appliedQuery)

    suspend fun initialize() {
        runtimeSettings = settingsRepository.observeSettings().first()
        registry.runtime.preset = runtimeSettings.scenarioPreset.toStubPreset()

        val modes = listOf(QueryMode.Unified) + SourceKey.entries.map(QueryMode::Source)
        modes.forEach { mode ->
            val stored = queryRepository.observeAppliedQuery(modeKey(mode)).first()
            if (stored != null) {
                appliedByMode[modeKey(mode)] = stored
            }
        }

        val unified = appliedByMode[modeKey(QueryMode.Unified)] ?: defaultQuery()
        appliedQuery = unified
        draftQuery = unified
    }

    fun onSettingsChanged(settings: AppSettings): Boolean {
        val oldScenario = runtimeSettings.scenarioPreset
        runtimeSettings = settings
        registry.runtime.preset = settings.scenarioPreset.toStubPreset()
        return hasExecutedSearch && oldScenario != settings.scenarioPreset
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
        val restored = appliedByMode[modeKey(mode)] ?: defaultQuery(mode)
        draftQuery = restored.copy(mode = mode)
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
                runtimeSettings.runtime.enabledSources
                    .flatMap { source ->
                        runCatching { registry.adapterFor(source).trendingTags(limit = 5) }
                            .getOrDefault(emptyList())
                    }
                    .distinctBy { it.text }
                    .take(20)
            }
            is QueryMode.Source -> {
                runCatching { registry.adapterFor(mode.source).trendingTags(limit = 20) }
                    .getOrDefault(emptyList())
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
        loading = true
        errorMessage = null
        statuses = emptyList()

        try {
            val enabledSources = runtimeSettings.runtime.enabledSources
            when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> {
                    val disabledStatuses = SourceKey.entries
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
                        weights = runtimeSettings.runtime.sourceWeights,
                    )
                    results = result.items
                    statuses = (result.statuses + disabledStatuses)
                        .distinctBy { it.source }
                        .sortedBy { it.source.name }
                }
                is QueryMode.Source -> {
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
                    val adapter = registry.adapterFor(mode.source)
                    val page = adapter.search(appliedQuery, pageToken = null)
                    results = page.items
                    statuses = listOf(SourceRunStatus(mode.source, state = SourceRunState.SUCCESS))
                }
            }
        } catch (error: Throwable) {
            results = emptyList()
            errorMessage = error.message ?: "Unknown error"
        } finally {
            hasExecutedSearch = true
            loading = false
        }
    }

    private fun modeKey(mode: QueryMode): String {
        return when (mode) {
            QueryMode.Unified -> "unified"
            is QueryMode.Source -> "source:${mode.source.name}"
        }
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

private fun ScenarioPreset.toStubPreset(): StubScenarioPreset {
    return when (this) {
        ScenarioPreset.NORMAL -> StubScenarioPreset.NORMAL
        ScenarioPreset.PARTIAL_FAILURE -> StubScenarioPreset.PARTIAL_FAILURE
        ScenarioPreset.EMPTY_RESULTS -> StubScenarioPreset.EMPTY_RESULTS
        ScenarioPreset.SLOW_NETWORK -> StubScenarioPreset.SLOW_NETWORK
    }
}

enum class DateRangePreset {
    NONE,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}
