package com.theoriacodex.sources.common

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAdapterCommonTest {
    @Test
    fun `source quick query maps popular windows and random fallback`() {
        val now = 1_700_000_000_000L
        val popular = sourceQuickQuery(SourceKey.GELBOORU, QuickQueryKind.POPULAR_TODAY, nowEpochMs = now)
        val random = sourceQuickQuery(SourceKey.GELBOORU, QuickQueryKind.RANDOM, nowEpochMs = now)

        assertEquals(QueryMode.Source(SourceKey.GELBOORU), popular.mode)
        assertEquals(SortMode.POPULAR, popular.sort)
        assertEquals(now, popular.dateRange?.toEpochMs)
        assertEquals(now - 24L * 60L * 60L * 1000L, popular.dateRange?.fromEpochMs)
        assertEquals(SortMode.NEWEST, random.sort)
        assertNull(random.dateRange)
    }

    @Test
    fun `flexible duration parser supports seconds milliseconds and clock strings`() {
        val obj = Gson().fromJson(
            """
            {
              "seconds": "10",
              "clock": "1:05",
              "millis": 10000,
              "ambiguousSeconds": 12,
              "ambiguousMillis": 12000
            }
            """.trimIndent(),
            JsonObject::class.java,
        )

        assertEquals(10_000L, obj.durationFieldMs("seconds", multiplier = 1_000L))
        assertEquals(65_000L, obj.durationFieldMs("clock", multiplier = 1_000L))
        assertEquals(10_000L, obj.durationFieldMs("millis", multiplier = 1L))
        assertEquals(12_000L, obj.ambiguousDurationFieldMs("ambiguousSeconds"))
        assertEquals(12_000L, obj.ambiguousDurationFieldMs("ambiguousMillis"))
        assertEquals(65_000L, parseFlexibleDurationMs("1:05"))
    }

    @Test
    fun `json helpers turn malformed payloads into typed parse failures`() {
        val failure = runCatching {
            parseJsonObject("not-json", Gson(), "Example")
        }.exceptionOrNull()

        assertTrue(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.PARSE, (failure as SourceAdapterException).reason)
    }

    @Test
    fun `mime and http helpers normalize common provider decisions`() {
        assertEquals("video/mp4", mimeFromUrlOrExt("https://example.com/file.mp4?x=1", null))
        assertEquals("image/jpeg", mimeFromUrlOrExt(null, "jpg"))
        assertNotNull(firstDurationMs(null, -1L, 5_000L))
        assertEquals(SourceFailureReason.AUTH_REQUIRED, classifyHttpFailure(403))
        assertEquals(SourceFailureReason.RATE_LIMITED, classifyHttpFailure(429))
        assertEquals(SourceFailureReason.NETWORK, classifyHttpFailure(503))
        assertEquals(
            SourceFailureReason.AUTH_REQUIRED,
            classifyHttpFailure(statusCode = 200, body = "api key required") { body ->
                "api key" in body
            },
        )
    }
}
