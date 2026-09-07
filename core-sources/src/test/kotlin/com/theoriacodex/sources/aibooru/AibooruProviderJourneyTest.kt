package com.theoriacodex.sources.aibooru

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.testing.ScriptedHttpClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AibooruProviderJourneyTest {
    @Test
    fun `filtered scored search keeps paging after duplicates and can retry a failed detail request`() = runTest {
        val query = query()
        val http = ScriptedHttpClient().apply {
            enqueueGet("$BASE/posts.json", searchParams("1"), body =
                (List(38) { postJson(41) } + postJson(42) + "null").joinToString(",", "[", "]"))
            enqueueGet("$BASE/posts.json", searchParams("2"), body = "[${postJson(43)}]")
            enqueueGet("$BASE/posts/42.json", statusCode = 503, body = "temporarily unavailable")
            enqueueGet("$BASE/posts/42.json", body = postJson(42))
        }
        val adapter = AibooruSourceAdapter(http)
        val first = adapter.search(query, null)
        assertEquals(listOf("41", "42"), first.items.map { it.id.sourcePostId })
        assertEquals("2", first.nextPageToken)
        val next = adapter.search(query, first.nextPageToken)
        assertEquals(listOf("43"), next.items.map { it.id.sourcePostId })
        assertNull(next.nextPageToken)

        val id = PostId(SourceKey.AIBOORU, "42")
        val failure = runCatching { adapter.resolvePost(id) }.exceptionOrNull()
        assertEquals(SourceFailureReason.NETWORK, (failure as SourceAdapterException).reason)
        val resolved = adapter.resolvePost(id)
        assertEquals(id, resolved?.id)
        assertEquals("$BASE/42.png", resolved?.full?.url)
        assertEquals(listOf("landscape", "sky"), resolved?.canonicalTags)
        assertEquals(listOf("41", "42"), first.items.map { it.id.sourcePostId })
        http.assertExhausted()
    }

    @Test
    fun `trending and autocomplete send their distinct requests and preserve provider counts`() = runTest {
        val tags = """[{"name":"sky","post_count":80},null,{"name":"sky_blue","count":20},{"name":" "}]"""
        val http = ScriptedHttpClient().apply {
            enqueueGet("$BASE/tags.json", mapOf("limit" to "50", "search[order]" to "count"), body = tags)
            enqueueGet("$BASE/tags.json", mapOf("limit" to "30", "search[name_matches]" to "sky*", "search[order]" to "count"), body = tags)
        }
        val adapter = AibooruSourceAdapter(http)
        val trending = adapter.trendingTags(80)
        val suggestions = adapter.autocompleteTags(" sky ", 80)
        assertEquals(listOf("sky" to 80, "sky_blue" to 20), trending.map { it.text to it.count })
        assertEquals(trending.map { it.text to it.count }, suggestions.map { it.text to it.count })
        assertEquals(emptyList<String>(), adapter.autocompleteTags(" ", 3).map { it.text })
        assertNull(adapter.resolvePost(PostId(SourceKey.PIXIV, "42")))
        http.assertExhausted()
    }

    @Test
    fun `provider parse transport rate limit and cancellation outcomes remain distinguishable`() = runTest {
        val cancellation = CancellationException("route left")
        val http = ScriptedHttpClient().apply {
            enqueueGet("$BASE/posts.json", searchParams("1"), body = "<html>unavailable</html>")
            enqueueGet("$BASE/posts.json", searchParams("1"), failure = IOException("disconnected"))
            enqueueGet("$BASE/posts.json", searchParams("1"), statusCode = 429)
            enqueueGet("$BASE/posts.json", searchParams("1"), failure = cancellation)
        }
        val adapter = AibooruSourceAdapter(http)
        for (reason in listOf(SourceFailureReason.PARSE, SourceFailureReason.NETWORK, SourceFailureReason.RATE_LIMITED)) {
            val failure = runCatching { adapter.search(query(), null) }.exceptionOrNull()
            assertEquals(reason, (failure as SourceAdapterException).reason)
        }
        assertSame(cancellation, runCatching { adapter.search(query(), null) }.exceptionOrNull())
        http.assertExhausted()
    }

    private fun query() = Query(
        mode = QueryMode.Source(SourceKey.AIBOORU), includeTags = listOf("landscape"), excludeTags = listOf("comic"),
        sort = SortMode.TOP, dateRange = null, minScore = 100,
    )

    private fun searchParams(page: String) = mapOf(
        "limit" to "40", "page" to page, "tags" to "landscape -comic score:>=100 order:score",
    )

    private fun postJson(id: Int) =
        """{"id":$id,"preview_file_url":"$BASE/$id.jpg","file_url":"$BASE/$id.png","tag_string":"landscape sky"}"""

    private companion object {
        const val BASE = "https://aibooru.online"
    }
}
