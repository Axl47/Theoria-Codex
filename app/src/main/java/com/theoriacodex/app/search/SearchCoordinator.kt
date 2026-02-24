package com.theoriacodex.app.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.theoriacodex.stubs.StubAdapterRegistry

class SearchCoordinator(
    private val registry: StubAdapterRegistry = StubAdapterRegistry(),
) {
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
        draftQuery = draftQuery.copy(mode = mode)
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
                SourceKey.entries
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
        executeSearch()
    }

    suspend fun retry() {
        executeSearch()
    }

    private suspend fun executeSearch() {
        loading = true
        errorMessage = null
        statuses = emptyList()

        try {
            when (val mode = appliedQuery.mode) {
                QueryMode.Unified -> {
                    val result = registry.unifiedOrchestrator().search(
                        query = appliedQuery,
                        enabledSources = SourceKey.entries.toSet(),
                        pageTokens = emptyMap(),
                        weights = mapOf(
                            SourceKey.PIXIV to 0.5,
                            SourceKey.GELBOORU to 0.3,
                            SourceKey.AIBOORU to 0.2,
                        ),
                    )
                    results = result.items
                    statuses = result.statuses
                }
                is QueryMode.Source -> {
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
            loading = false
        }
    }

    private companion object {
        fun defaultQuery(): Query {
            return Query(
                mode = QueryMode.Unified,
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            )
        }
    }
}

enum class DateRangePreset {
    NONE,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}
