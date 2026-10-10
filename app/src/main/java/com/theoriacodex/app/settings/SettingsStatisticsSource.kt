package com.theoriacodex.app.settings

import com.theoriacodex.app.codex.codexBelongsToProfile
import com.theoriacodex.app.statistics.AppUsageTracker
import com.theoriacodex.app.statistics.StatisticsProjection
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.statistics.withLiveUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal class SettingsStatisticsSource(
    private val statisticsRepository: StatisticsRepository,
    private val codexRepository: CodexRepository,
    private val appUsageTracker: AppUsageTracker,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observe(activeProfileIds: Flow<String>): Flow<StatisticsSummary> {
        val content = activeProfileIds
            .map(String::trim)
            .distinctUntilChanged()
            .flatMapLatest(::observeProfileContent)
            .distinctUntilChanged()
            .map { StatisticsProjection.prepareContent(it.codices, it.postsByCodex) }
            .flowOn(computationDispatcher)
        val settled = combine(statisticsRepository.observeStatistics(), content) { lifetime, saved ->
            StatisticsProjection.build(lifetime = lifetime, savedContent = saved)
        }.flowOn(computationDispatcher)
        return combine(settled, appUsageTracker.liveUsage) { summary, usage -> summary.withLiveUsage(usage) }
    }

    private fun observeProfileContent(profileId: String): Flow<SettingsStatisticsContent> {
        return codexRepository.observeCodices().flatMapLatest { allCodices ->
            val visibleCodices = allCodices.filter { codex ->
                codexBelongsToProfile(codex.codexId, profileId)
            }
            if (visibleCodices.isEmpty()) {
                flowOf(SettingsStatisticsContent())
            } else {
                combine(
                    visibleCodices.map { codex ->
                        codexRepository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED)
                            .map { posts -> codex.codexId to posts }
                    }
                ) { postsByCodex ->
                    SettingsStatisticsContent(
                        codices = visibleCodices,
                        postsByCodex = postsByCodex.toMap(),
                    )
                }
            }
        }
    }
}

private data class SettingsStatisticsContent(
    val codices: List<Codex> = emptyList(),
    val postsByCodex: Map<String, List<Post>> = emptyMap(),
)
