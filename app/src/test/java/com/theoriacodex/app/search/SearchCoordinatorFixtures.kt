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
import com.theoriacodex.domain.adapter.SourceAdapterRegistry

internal fun testSearchCoordinator(
    registry: SourceAdapterRegistry,
    queryRepository: QueryRepository = InMemoryQueryRepository(),
    settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    uiRestoreRepository: UiRestoreRepository = InMemoryUiRestoreRepository(),
    recentsRepository: RecentsRepository = InMemoryRecentsRepository(),
    statisticsRepository: StatisticsRepository = InMemoryStatisticsRepository(),
    tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    clock: () -> Long = System::currentTimeMillis,
): SearchCoordinator {
    return SearchCoordinator(
        registry = registry,
        queryRepository = queryRepository,
        settingsRepository = settingsRepository,
        uiRestoreRepository = uiRestoreRepository,
        recentsRepository = recentsRepository,
        statisticsRepository = statisticsRepository,
        tagSuggestionStore = tagSuggestionStore,
        clock = clock,
    )
}
