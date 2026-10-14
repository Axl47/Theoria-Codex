package com.theoriacodex.sources.common

import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import com.theoriacodex.sources.rule34.Rule34VideoSourceAdapter
import com.theoriacodex.sources.rule34.Rule34GenSourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteTagCountsTest {
    @Test fun `video providers retain their authoritative counts through the shared capability`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200,
                """{"items":[{"title":"cat_neighbor","total":"999"},{"title":"cat","total":"21"}]}""")
        }
        for (source in listOf(Rule34VideoSourceAdapter(http), Rule34GenSourceAdapter(http))) {
            assertEquals(mapOf("cat" to 21), source.fetchTagCounts(listOf("cat")))
        }
    }

    @Test fun `exact lookup is normalized deduplicated and bounded while neighbors and missing counts stay absent`() = runTest {
        var active = 0
        var maximum = 0
        val requested = mutableListOf<String>()
        val result = autocompleteTagCounts(SourceKey.HITOMI, listOf(" cat ", "CAT", "dog", "zero", "missing")) { tag, limit ->
            requested += tag
            assertEquals(30, limit)
            active++
            maximum = maxOf(maximum, active)
            delay(10)
            active--
            listOf(TagSuggestion("${tag}_neighbor", "tag", 999),
                TagSuggestion(tag.uppercase(), "tag", when (tag) { "missing" -> null; "zero" -> 0; else -> 7 }))
        }
        assertEquals(mapOf("cat" to 7, "dog" to 7, "zero" to 0), result)
        assertEquals(listOf("cat", "dog", "zero", "missing"), requested)
        assertEquals(2, maximum)
    }

    @Test fun `provider cancellation remains cancellation`() = runTest {
        val result = runCatching {
            autocompleteTagCounts(SourceKey.AIBOORU, listOf("cat")) { _, _ -> throw CancellationException("left") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
