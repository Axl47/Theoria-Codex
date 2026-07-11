package com.theoriacodex.sources.common

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.QuickQueryKind
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
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
        assertEquals(
            SourceFailureReason.AUTH_EXPIRED,
            classifyHttpFailure(
                statusCode = 401,
                unauthorizedReason = SourceFailureReason.AUTH_EXPIRED,
            ),
        )
        assertTrue(SourceHttpResponse(statusCode = 204, body = "").isSuccessful())
    }

    @Test
    fun `safe json accessors tolerate nulls wrong shapes and numeric strings`() {
        val root = Gson().fromJson(
            """
            {
              "id": "42",
              "count": 7,
              "enabled": "true",
              "disabled": "FALSE",
              "unknown": "unknown",
              "nested": {"name": "example"},
              "items": [{"id": 1}],
              "wrong": []
            }
            """.trimIndent(),
            JsonObject::class.java,
        )

        assertEquals("42", root.get("id").asStringOrNull())
        assertEquals(42L, root.get("id").asLongOrNull())
        assertEquals(7, root.get("count").asIntOrNull())
        assertEquals(true, root.get("enabled").asBooleanOrNull())
        assertEquals(false, root.get("disabled").asBooleanOrNull())
        assertNull(root.get("unknown").asBooleanOrNull())
        assertEquals("example", root.optionalJsonObject("nested")?.get("name").asStringOrNull())
        assertEquals(1, root.optionalJsonArray("items")?.size())
        assertNull(root.optionalJsonObject("wrong"))
        assertNull(root.get("items").asLongOrNull())
        assertEquals(emptyList<JsonElement>(), (null as JsonArray?).elementsOrEmpty())
    }

    @Test
    fun `duration helpers reject non-finite and overflowed values`() {
        val root = Gson().fromJson(
            """
            {
              "infiniteNumber": 1e309,
              "infiniteString": "Infinity",
              "nanString": "NaN",
              "overflowedSeconds": "1e309"
            }
            """.trimIndent(),
            JsonObject::class.java,
        )

        assertNull(root.durationFieldMs("infiniteNumber", multiplier = 1_000L))
        assertNull(root.durationFieldMs("infiniteString", multiplier = 1_000L))
        assertNull(root.durationFieldMs("nanString", multiplier = 1_000L))
        assertNull(root.ambiguousDurationFieldMs("infiniteNumber"))
        assertNull(parseFlexibleDurationMs("1e309"))
        assertNull(parseFlexibleDurationMs("9223372036854775807:00"))
    }

    @Test
    fun `object list accepts direct arrays and named envelopes`() {
        val gson = Gson()
        val direct = gson.fromJson("""[{"id":1},null,"skip"]""", JsonElement::class.java)
        val enveloped = gson.fromJson("""{"post":{"id":2}}""", JsonElement::class.java)

        assertEquals(listOf(1), direct.objectList("post").mapNotNull { it.get("id").asIntOrNull() })
        assertEquals(listOf(2), enveloped.objectList("post").mapNotNull { it.get("id").asIntOrNull() })
    }

    @Test
    fun `challenge matching is case insensitive and provider configurable`() {
        val headerChallenge = SourceHttpResponse(
            statusCode = 403,
            body = "",
            headers = mapOf("CF-Mitigated" to listOf("Challenge")),
        )
        val bodyChallenge = SourceHttpResponse(
            statusCode = 403,
            body = "Attention Required",
        )

        assertTrue(headerChallenge.matchesChallenge())
        assertTrue(bodyChallenge.matchesChallenge(bodyMarkers = setOf("attention required")))
        assertTrue(!bodyChallenge.matchesChallenge(bodyMarkers = setOf("cloudflare")))
    }
}
