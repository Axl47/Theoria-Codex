package com.theoriacodex.app.recommend

import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.InMemorySavedSearchRepository
import com.theoriacodex.data.repository.InMemorySettingsRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThrottleLatestTest {
    @Test
    fun `the first value passes at once and a burst settles on its newest value once per period (ADR 0215)`() = runTest {
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Int>()
        backgroundScope.launch { source.throttleLatest(TAG_AFFINITY_STATISTICS_PERIOD).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(0), seen)

        (1..30).forEach { view ->
            source.value = view
            advanceTimeBy(100)
        }
        assertEquals(listOf(0), seen)

        advanceTimeBy(TAG_AFFINITY_STATISTICS_PERIOD.inWholeMilliseconds)
        assertEquals(listOf(0, 30), seen)

        advanceTimeBy(TAG_AFFINITY_STATISTICS_PERIOD.inWholeMilliseconds * 2)
        assertEquals(listOf(0, 30), seen)
    }

    @Test
    fun `tag affinity reads a burst of views once per period with the newest counts (ADR 0215)`() = runTest {
        val statistics = InMemoryStatisticsRepository()
        val settings = InMemorySettingsRepository()
        val profile = settings.observeSettings().first().activeProfileId
        val affinity = shareTagAffinityInputs(
            backgroundScope, settings, InMemoryLikesRepository(), InMemoryCodexRepository(), InMemorySavedSearchRepository(),
            statistics, computationDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertNull(affinity.forProfile(profile).watchedBySource[SourceKey.GELBOORU])

        repeat(VIEWS) {
            statistics.recordWatchedPost(SourceKey.GELBOORU, setOf("shared"))
            advanceTimeBy(100)
        }
        runCurrent()
        assertNull(affinity.forProfile(profile).watchedBySource[SourceKey.GELBOORU])

        advanceTimeBy(TAG_AFFINITY_STATISTICS_PERIOD.inWholeMilliseconds)
        runCurrent()
        assertEquals(VIEWS.toLong(), affinity.forProfile(profile).watchedBySource[SourceKey.GELBOORU])
    }

    private companion object {
        const val VIEWS = 30
    }
}
