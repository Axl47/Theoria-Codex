package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.testing.ScriptedHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Rule34GenProviderJourneyTest {
    @Test
    fun `search follows provider continuation deduplicates cards and retries a failed video page`() = runTest {
        val headers = mapOf("Referer" to "$BASE/")
        val http = ScriptedHttpClient().apply {
            enqueueGet("$BASE/search/landscape/", body =
                """<div data-block-next="$BASE/search/landscape/2/">${card(42)}${card(42)}${card(43)}</div>""",
                requiredHeaders = headers)
            enqueueGet("$BASE/search/landscape/2/", body = card(44), requiredHeaders = headers)
            enqueueGet("$BASE/video/43/x/", body = "unavailable", statusCode = 503, requiredHeaders = headers)
            enqueueGet("$BASE/video/43/x/", body = """
                <link rel="canonical" href="$BASE/video/43/landscape/">
                <script>var flashvars = {video_id: '43', video_title: 'Landscape', video_tags: 'landscape, sky',
                video_duration: '90', video_url: '$BASE/media/43.mp4', video_url_text: '720p',
                preview_url: '$BASE/43.jpg'}; kt_player('player', '/player.swf', '100%', '100%', flashvars);</script>
            """.trimIndent(), requiredHeaders = headers)
            enqueueGet("$BASE/video/44/x/", statusCode = 404, requiredHeaders = headers)
        }
        val adapter = Rule34GenSourceAdapter(http)
        val query = Query(QueryMode.Source(SourceKey.RULE34GEN), includeTags = listOf("landscape"),
            excludeTags = emptyList(), sort = SortMode.NEWEST, dateRange = null, minScore = null)
        val first = adapter.search(query, null)
        assertEquals(listOf("42", "43"), first.items.map { it.id.sourcePostId })
        assertEquals("$BASE/search/landscape/2/", first.nextPageToken)
        val second = adapter.search(query, first.nextPageToken)
        assertEquals(listOf("44"), second.items.map { it.id.sourcePostId })
        assertNull(second.nextPageToken)

        val id = PostId(SourceKey.RULE34GEN, "43")
        assertEquals(SourceFailureReason.NETWORK,
            (runCatching { adapter.resolvePost(id) }.exceptionOrNull() as SourceAdapterException).reason)
        val resolved = adapter.resolvePost(id)
        assertEquals(id, resolved?.id)
        assertEquals("$BASE/media/43.mp4", resolved?.full?.url)
        assertEquals(90_000L, resolved?.durationMs)
        assertEquals(listOf("landscape", "sky"), resolved?.canonicalTags)
        assertNull(adapter.resolvePost(PostId(SourceKey.RULE34GEN, "44")))
        http.assertExhausted()
    }

    private fun card(id: Int) = """
        <div class="cards__item"><a class="card" href="$BASE/video/$id/landscape/" title="Landscape">
        <img src="$BASE/$id.jpg"><span class="duration">1:30</span></a></div>
    """.trimIndent()

    private companion object {
        const val BASE = "https://rule34gen.com"
    }
}
