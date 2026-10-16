package com.theoriacodex.app.statistics

import com.theoriacodex.app.ui.statisticsUsageCategory
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUsageTrackerTest {
    @Test
    fun `codex timing follows collection and selected media and excludes background and unrelated routes`() = runTest {
        var time = 0L
        val repository = InMemoryStatisticsRepository()
        val tracker = AppUsageTracker(repository, this, { time }, tickIntervalMs = 0L)
        suspend fun target(value: com.theoriacodex.data.repository.CodexUsageTarget?) {
            tracker.onCodexTargetChanged(value)
            tracker.awaitIdle()
        }
        tracker.onForeground()
        target(com.theoriacodex.data.repository.CodexUsageTarget("first"))
        time = 100L
        target(com.theoriacodex.data.repository.CodexUsageTarget("first", "PIXIV:1", video = true))
        time = 350L
        target(com.theoriacodex.data.repository.CodexUsageTarget("first", "PIXIV:1"))
        time = 400L
        tracker.onBackground()
        tracker.awaitIdle()
        time = 10_000L
        tracker.onForeground()
        tracker.awaitIdle()
        time = 10_050L
        target(com.theoriacodex.data.repository.CodexUsageTarget("second"))
        time = 10_100L
        target(null)
        time = 20_000L
        tracker.onBackground()
        tracker.awaitIdle()
        val stats = repository.observeStatistics().first().codexStatistics
        assertEquals(100L, stats.getValue("first").browsingMs)
        assertEquals(350L, stats.getValue("first").watchingMs)
        assertEquals(250L, stats.getValue("first").posts.getValue("PIXIV:1").videoMs)
        assertEquals(100L, stats.getValue("first").posts.getValue("PIXIV:1").imageMs)
        assertEquals(50L, stats.getValue("second").browsingMs)
        tracker.close()
    }

    @Test
    fun `foreground timing uses mutually exclusive categories and ignores duplicate lifecycle events`() = runTest {
        var elapsedMs = 0L
        val repository = InMemoryStatisticsRepository()
        val tracker = AppUsageTracker(
            repository = repository,
            scope = this,
            elapsedRealtime = { elapsedMs },
            tickIntervalMs = 0L,
        )

        tracker.onCategoryChanged(StatisticsUsageCategory.BROWSING)
        tracker.onForeground()
        tracker.onForeground()
        tracker.awaitIdle()
        elapsedMs = 2_500L
        tracker.refreshLiveUsage()
        tracker.awaitIdle()
        assertEquals(2_500L, tracker.liveUsage.value.totalMs)
        assertEquals(2_500L, tracker.liveUsage.value.browsingMs)

        tracker.onCategoryChanged(StatisticsUsageCategory.WATCHING)
        tracker.awaitIdle()
        elapsedMs = 4_000L
        tracker.onBackground()
        tracker.onBackground()
        tracker.awaitIdle()

        val statistics = repository.observeStatistics().first()
        assertEquals(1L, statistics.appOpenCount)
        assertEquals(4_000L, statistics.totalForegroundMs)
        assertEquals(2_500L, statistics.browsingMs)
        assertEquals(1_500L, statistics.watchingMs)
        assertEquals(0L, statistics.codexMs)
        assertEquals(0L, tracker.liveUsage.value.totalMs)
        tracker.close()
    }

    @Test
    fun `returning from background records one new open and uncategorized time remains total only`() = runTest {
        var elapsedMs = 10L
        val repository = InMemoryStatisticsRepository()
        val tracker = AppUsageTracker(repository, this, { elapsedMs }, tickIntervalMs = 0L)

        tracker.onForeground()
        tracker.awaitIdle()
        elapsedMs = 1_010L
        tracker.onBackground()
        tracker.awaitIdle()
        elapsedMs = 2_010L
        tracker.onForeground()
        tracker.awaitIdle()
        elapsedMs = 2_510L
        tracker.onBackground()
        tracker.awaitIdle()

        val statistics = repository.observeStatistics().first()
        assertEquals(2L, statistics.appOpenCount)
        assertEquals(1_500L, statistics.totalForegroundMs)
        assertEquals(0L, statistics.browsingMs)
        assertEquals(0L, statistics.watchingMs)
        assertEquals(0L, statistics.codexMs)
        tracker.close()
    }

    @Test
    fun `route categories map home tabs and secondary destinations without overlap`() {
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("home", "search"))
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("home", "recents"))
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("home", "for_you"))
        assertEquals(StatisticsUsageCategory.CODEX, statisticsUsageCategory("home", "codex"))
        assertNull(statisticsUsageCategory("home", "settings"))
        assertEquals(StatisticsUsageCategory.WATCHING, statisticsUsageCategory("viewer", "search"))
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("creator-profile", "settings"))
        assertEquals(StatisticsUsageCategory.CODEX, statisticsUsageCategory("codex/detail/{codexId}", "search"))
    }
}
