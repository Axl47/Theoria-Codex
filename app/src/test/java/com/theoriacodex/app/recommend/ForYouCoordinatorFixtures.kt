package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import kotlinx.coroutines.Dispatchers

/** A coordinator that builds lanes inline and keeps interests in weight order. */
internal fun testForYouCoordinator(
    registry: SourceAdapterRegistry,
    likesRepository: LikesRepository = InMemoryLikesRepository(),
    statisticsRepository: StatisticsRepository = InMemoryStatisticsRepository(),
    tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    tagAffinity: TagAffinityInputs = TagAffinityInputs.Empty,
): ForYouCoordinator = ForYouCoordinator(
    registry = registry,
    likesRepository = likesRepository,
    statisticsRepository = statisticsRepository,
    tagAffinity = { tagAffinity },
    tagSuggestionStore = tagSuggestionStore,
    computationDispatcher = Dispatchers.Unconfined,
    random = { null },
)
