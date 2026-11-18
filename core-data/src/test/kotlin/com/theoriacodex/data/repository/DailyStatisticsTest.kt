package com.theoriacodex.data.repository

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
            val stored = Json.parseToJsonElement(directory.resolve(DATASTORE_STATISTICS_FILE_NAME).readText())
            assertEquals(400, stored.jsonObject.getValue("statistics").jsonObject.getValue("history").jsonObject.size)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
