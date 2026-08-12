package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryStatisticsRepository(
    initial: LifetimeStatistics = LifetimeStatistics(),
) : StatisticsRepository {
    private val mutex = Mutex()
    private val mutableStatistics = MutableStateFlow(StatisticsPolicies.normalize(initial))

    override fun observeStatistics(): Flow<LifetimeStatistics> = mutableStatistics

    override suspend fun recordAppOpen() = mutate(StatisticsPolicies::recordAppOpen)

    override suspend fun addUsageDuration(delta: UsageDurationDelta) = mutate { current ->
        StatisticsPolicies.addUsage(current, delta)
    }

    override suspend fun recordWatchedPost(source: SourceKey, tags: Set<String>) = mutate { current ->
        StatisticsPolicies.recordWatched(current, source, tags)
    }

    override suspend fun recordSearch(sources: Set<SourceKey>) = mutate { current ->
        StatisticsPolicies.recordSearch(current, sources)
    }

    override suspend fun recordForYouSearch() = mutate(StatisticsPolicies::recordForYouSearch)

    override suspend fun recordForYouSave() = mutate(StatisticsPolicies::recordForYouSave)

    override suspend fun recordPostUrlCopy() = mutate(StatisticsPolicies::recordPostUrlCopy)

    override suspend fun recordCodexEntry(codexId: String) = mutate { current ->
        StatisticsPolicies.recordCodexEntry(current, codexId)
    }

    private suspend fun mutate(transform: (LifetimeStatistics) -> LifetimeStatistics) {
        mutex.withLock {
            mutableStatistics.value = StatisticsPolicies.normalize(transform(mutableStatistics.value))
        }
    }
}
