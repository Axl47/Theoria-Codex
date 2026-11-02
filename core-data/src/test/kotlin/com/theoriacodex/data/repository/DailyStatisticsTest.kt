package com.theoriacodex.data.repository

import com.google.gson.JsonParser
import com.theoriacodex.domain.model.SourceKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DailyStatisticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `midnight slices retain foreground Other and respect a DST day`() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 7).atTime(23, 59, 59).atZone(zone).toInstant()
        val slices = splitDailyUsage(start, zone, UsageDurationDelta(totalMs = 3_000L))
        assertEquals(listOf(1_000L, 2_000L), slices.map { it.usage.totalMs })
        assertTrue(slices.all { it.usage.browsingMs + it.usage.watchingMs + it.usage.codexMs == 0L })
        val dstStart = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant()
        val dst = splitDailyUsage(dstStart, zone, UsageDurationDelta(totalMs = 24 * 3_600_000L,
            watchingMs = 24 * 3_600_000L))
        assertEquals(listOf(23 * 3_600_000L, 3_600_000L), dst.map { it.usage.watchingMs })
    }

    @Test fun `v1 disk migration preserves all fields starts empty history and persists v2`() = runTest {
        val directory = temporary.newFolder()
        val file = directory.resolve(DATASTORE_STATISTICS_FILE_NAME)
        val v1 = requireNotNull(javaClass.getResource("/statistics/v1.json")).readText()
        file.writeText(v1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val clock = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneId.of("America/Puerto_Rico"))
            val repository = DataStoreStatisticsRepository(directory, scope, clock = clock)
            val migrated = repository.observeStatistics().first()
            assertTrue(migrated.history.isEmpty())
            val actual = JsonParser.parseString(file.readText()).asJsonObject
            assertEquals(2, actual["schemaVersion"].asInt)
            assertEquals(0, actual["statistics"].asJsonObject.remove("history").asJsonObject.size())
            assertEquals(JsonParser.parseString(v1).asJsonObject["statistics"], actual["statistics"])
            repository.recordAppOpen()
            repository.recordWatchedPost(SourceKey.PIXIV, emptySet())
            repository.recordSearch(setOf(SourceKey.PIXIV))
            val updated = repository.observeStatistics().first()
            assertEquals(migrated.copy(appOpenCount = 20L, watchedPostCount = 14L,
                watchedBySource = mapOf(SourceKey.PIXIV to 14L), searchCount = 12L,
                searchesBySource = migrated.searchesBySource + (SourceKey.PIXIV to 1L),
                history = mapOf(LocalDate.of(2026, 11, 2) to DailyActivity(appOpenCount = 1,
                    watchedPostCount = 1, searchCount = 1))), updated)
            assertFalse(directory.listFiles().orEmpty().any { it.name.contains("corrupt") })
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    @Test fun `history is bounded by calendar age and count without changing lifetime totals`() = runTest {
        val directory = temporary.newFolder()
        val today = LocalDate.of(2026, 11, 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreStatisticsRepository(directory, scope,
                clock = Clock.fixed(today.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC")))
            val slices = (0L..450L).map { DailyUsageDuration(today.minusDays(it), UsageDurationDelta(totalMs = 10L)) }
            repository.addUsageDuration(UsageDurationDelta(totalMs = 4510L, dailyUsage = slices))
            val result = repository.observeStatistics().first()
            assertEquals(400, result.history.size)
            assertEquals(today.minusDays(399), result.history.keys.min())
            assertEquals(4510L, result.totalForegroundMs)
            assertEquals(4000L, result.history.values.sumOf { it.totalForegroundMs })
            assertEquals(400, JsonParser.parseString(directory.resolve(DATASTORE_STATISTICS_FILE_NAME).readText())
                .asJsonObject["statistics"].asJsonObject["history"].asJsonObject.size())
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
