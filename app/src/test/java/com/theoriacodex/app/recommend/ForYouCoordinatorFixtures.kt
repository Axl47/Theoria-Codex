package com.theoriacodex.app.recommend

import com.theoriacodex.app.search.NoOpTagSuggestionStore
import com.theoriacodex.app.search.TagSuggestionStore
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.recommendation.LikedTagDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * A coordinator that builds lanes inline and keeps interests in weight order. Lanes read Likes from the affinity inputs,
 * as the shared inputs carry them, so [likesRepository]'s Likes are added to [tagAffinity].
 */
internal fun testForYouCoordinator(
    registry: SourceAdapterRegistry,
    likesRepository: LikesRepository = InMemoryLikesRepository(),
    statisticsRepository: StatisticsRepository = InMemoryStatisticsRepository(),
    tagSuggestionStore: TagSuggestionStore = NoOpTagSuggestionStore,
    tagAffinity: TagAffinityInputs = TagAffinityInputs.Empty,
): ForYouCoordinator = ForYouCoordinator(
    registry = registry,
    statisticsRepository = statisticsRepository,
    tagAffinity = { profile ->
        tagAffinity.copy(liked = likesRepository.observeLikes(profile).first().map { LikedTagDocument(it.postId.source, it.tags) })
    },
    tagSuggestionStore = tagSuggestionStore,
    computationDispatcher = Dispatchers.Unconfined,
    random = { null },
)
