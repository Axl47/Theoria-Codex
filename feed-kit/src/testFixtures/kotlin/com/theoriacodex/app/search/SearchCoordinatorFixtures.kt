package com.theoriacodex.app.search

import com.theoriacodex.data.repository.InMemoryQueryRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.InMemoryUiRestoreRepository
import com.theoriacodex.data.repository.QueryRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UiRestoreRepository
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

fun testSearchCoordinator(
    registry: SourceAdapterRegistry,
    queryRepository: QueryRepository = InMemoryQueryRepository(),
    settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
    recentsRepository: RecentsRepository = InMemoryRecentsRepository(),
    statisticsRepository: StatisticsRepository = InMemoryStatisticsRepository(),
    tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    firstPageStore: com.theoriacodex.data.repository.SearchFirstPageStore? = null,
    clock: () -> Long = System::currentTimeMillis,
    computationDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    seenTagScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + computationDispatcher),
    postResolutions: com.theoriacodex.app.source.PostResolutionFlights = com.theoriacodex.app.source.PostResolutionFlights(),
): SearchCoordinator {
    return SearchCoordinator(
        registry = registry,
        queryRepository = queryRepository,
        settingsRepository = settingsRepository,
        uiRestoreRepository = uiRestoreRepository,
        recentsRepository = recentsRepository,
        statisticsRepository = statisticsRepository,
        tagSuggestionStore = tagSuggestionStore,
        firstPageStore = firstPageStore,
        clock = clock,
        computationDispatcher = computationDispatcher,
        seenTagScope = seenTagScope,
        postResolutions = postResolutions,
    )
}

fun SearchCoordinator.defaultSources(scope: SearchSourceScope): SearchSources =
    SearchSources.of(SourceRuntimeSettings(), scope, availableSources.toSet())

suspend fun SearchCoordinator.executeInitial(query: Query, sourceScope: SearchSourceScope): SearchExecutionResult =
    executeInitial(query, sourceScope, defaultSources(sourceScope))

suspend fun SearchCoordinator.retrySource(
    query: Query,
    sourceScope: SearchSourceScope,
    previous: SearchContinuation?,
    source: SourceKey,
): SearchPageResult = retrySource(query, sourceScope, defaultSources(sourceScope), previous, source)

suspend fun SearchCoordinator.fetchAutocomplete(
    query: Query,
    sourceScope: SearchSourceScope,
    selectedScope: FacetedSearchScope,
    input: String,
    onUpdate: suspend (SearchAutocompleteResult) -> Unit = {},
): SearchAutocompleteResult = suggestions.fetchAutocomplete(query, defaultSources(sourceScope).enabled, selectedScope, input, onUpdate)

fun SearchCoordinator.cachedAutocomplete(
    query: Query,
    sourceScope: SearchSourceScope,
    selectedScope: FacetedSearchScope,
    input: String,
): SearchAutocompleteResult = suggestions.cachedAutocomplete(query, defaultSources(sourceScope).enabled, selectedScope, input)

suspend fun SearchCoordinator.fetchTrending(
    query: Query,
    sourceScope: SearchSourceScope,
    forceRefresh: Boolean = false,
): List<TagSuggestion> = suggestions.fetchTrending(query, defaultSources(sourceScope).enabled, forceRefresh)

fun SearchCoordinator.cachedTrending(query: Query, sourceScope: SearchSourceScope): List<TagSuggestion> =
    suggestions.cachedTrending(query, defaultSources(sourceScope).enabled)
