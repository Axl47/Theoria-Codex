package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemoryRecentsRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.RecentsRepository
import com.theoriacodex.data.repository.SettingsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry

internal fun testForYouCoordinator(
    registry: SourceAdapterRegistry,
    settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    likesRepository: LikesRepository = InMemoryLikesRepository(),
    recentsRepository: RecentsRepository = InMemoryRecentsRepository(),
    statisticsRepository: StatisticsRepository = InMemoryStatisticsRepository(),
    tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    seedSource: () -> Long = System::currentTimeMillis,
): ForYouCoordinator {
    return ForYouCoordinator(
        registry = registry,
        settingsRepository = settingsRepository,
        likesRepository = likesRepository,
        recentsRepository = recentsRepository,
        statisticsRepository = statisticsRepository,
        tagSuggestionStore = tagSuggestionStore,
        seedSource = seedSource,
    )
}
