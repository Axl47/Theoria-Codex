package com.theoriacodex.app.statistics

import com.theoriacodex.app.viewer.ViewerSeries
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.app.ui.statisticsUsageCategory
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.UsageDurationDelta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUsageTrackerTest {
    @Test
    fun `going to the background saves its usage and then flushes the store`() = runTest {
        var elapsed = 0L
        val calls = mutableListOf<String>()
        val memory = InMemoryStatisticsRepository()
        val repository = object : StatisticsRepository by memory {
            override suspend fun addUsageDuration(delta: UsageDurationDelta) {
                memory.addUsageDuration(delta)
                calls += "usage"
            }

            override suspend fun flush() {
                calls += "flush"
            }
        }
        val tracker = AppUsageTracker(repository, this, { elapsed }, tickIntervalMs = 0L)
        tracker.onForeground()
        tracker.awaitIdle()
        elapsed = 2_000L

        tracker.onBackground()
        tracker.awaitIdle()

        assertEquals(listOf("usage", "flush"), calls)
        tracker.close()
    }

    @Test
    fun `live midnight slices are memory only and flush exactly once including Other`() = runTest {
        var elapsed = 0L
        val zone = java.time.ZoneId.of("America/Puerto_Rico")
        val date = java.time.LocalDate.now(zone)
        val clock = java.time.Clock.fixed(date.atTime(23, 59, 59).atZone(zone).toInstant(), zone)
        val repository = InMemoryStatisticsRepository()
        val tracker = AppUsageTracker(repository, this, { elapsed }, tickIntervalMs = 0L, clock = clock)
        tracker.onForeground()
        tracker.awaitIdle()
        elapsed = 3_000L
        tracker.refreshLiveUsage()
        tracker.awaitIdle()
        val settled = repository.observeStatistics().first()
        assertEquals(0L, settled.totalForegroundMs)
        assertEquals(0L, settled.history.values.sumOf { it.totalForegroundMs })
        val live = StatisticsProjection.build(settled).withLiveUsage(tracker.liveUsage.value)
        assertEquals(listOf(1_000L, 2_000L), live.history.map { it.totalForegroundMs })
        assertEquals(0L, live.history.sumOf { it.browsingMs + it.watchingMs + it.codexMs })
        tracker.onBackground()
        tracker.awaitIdle()
        val flushed = repository.observeStatistics().first()
        assertEquals(live.history, StatisticsProjection.build(flushed).history)
        assertEquals(3_000L, flushed.totalForegroundMs)
        assertEquals(0L, tracker.liveUsage.value.totalMs)
        tracker.close()
    }

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
        tracker.onCategoryChanged(StatisticsUsageCategory.READING)
        tracker.awaitIdle()
        elapsedMs = 4_700L
        tracker.onCategoryChanged(StatisticsUsageCategory.ANIME)
        tracker.awaitIdle()
        elapsedMs = 5_000L
        tracker.onBackground()
        tracker.onBackground()
        tracker.awaitIdle()

        val statistics = repository.observeStatistics().first()
        assertEquals(1L, statistics.appOpenCount)
        assertEquals(5_000L, statistics.totalForegroundMs)
        assertEquals(2_500L, statistics.browsingMs)
        assertEquals(1_500L, statistics.watchingMs)
        assertEquals(0L, statistics.codexMs)
        assertEquals(700L, statistics.readingMs)
        assertEquals(300L, statistics.animeMs)
        val day = statistics.history.values.single()
        assertEquals(listOf(5_000L, 2_500L, 1_500L, 0L, 700L, 300L),
            listOf(day.totalForegroundMs, day.browsingMs, day.watchingMs, day.codexMs, day.readingMs, day.animeMs))
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
        val codices = LibrarySection.Codices
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("home", "search", codices))
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("home", "for_you", codices))
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("home", "library", LibrarySection.History))
        assertEquals(StatisticsUsageCategory.CODEX, statisticsUsageCategory("home", "library", codices))
        assertEquals(StatisticsUsageCategory.CODEX, statisticsUsageCategory("home", "library", LibrarySection.Saved))
        assertNull(statisticsUsageCategory("settings-screen", "search", codices))
        assertEquals(StatisticsUsageCategory.WATCHING, statisticsUsageCategory("viewer", "search", codices))
        assertEquals(StatisticsUsageCategory.WATCHING, statisticsUsageCategory("viewer", "search", codices, session(ViewerStreamSource.SEARCH)))
        val reading = session(ViewerStreamSource.SERIES, ViewerSeries.Reading(GalleryReadingMode.LEFT_TO_RIGHT))
        assertEquals(StatisticsUsageCategory.READING, statisticsUsageCategory("viewer", "series", codices, reading))
        val watching = session(ViewerStreamSource.SERIES, ViewerSeries.Watching())
        assertEquals(StatisticsUsageCategory.ANIME, statisticsUsageCategory("viewer", "series", codices, watching))
        assertNull(statisticsUsageCategory("settings-screen", "search", codices, watching))
        assertEquals(StatisticsUsageCategory.BROWSING, statisticsUsageCategory("creator-profile", "library", codices))
        assertEquals(StatisticsUsageCategory.CODEX, statisticsUsageCategory("codex/detail/{codexId}", "search", codices))
    }

    private fun session(stream: ViewerStreamSource, series: ViewerSeries? = null) =
        ViewerSession(emptyList(), ViewerLaunchContext("query", 0, stream, scrollOffsetHint = 0), series = series)
}
