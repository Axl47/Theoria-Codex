package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rule34PahealSourceAdapterTest {
    @Test
    fun `search parses rss items and next link`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAMPLE_PAHEAL_RSS)
        }
        val adapter = Rule34PahealSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals("https://rule34.paheal.net/rss/images/genshin_impact/1", httpClient.lastGet?.url)
        assertEquals("https://rule34.paheal.net/rss/images/genshin_impact/2", page.nextPageToken)
        assertEquals("7242058", page.items.first().id.sourcePostId)
        assertEquals("https://rule34.paheal.net/post/view/7242058", page.items.first().pageUrl)
        assertEquals("https://r34i.paheal-cdn.net/b9/68/b968fffb55cb42905b457b6f82a2c54a", page.items.first().full?.url)
    }

    @Test
    fun `autocomplete parses browser search json`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """["genshin",["genshin_impact","genshinman"],[],[]]""",
            )
        }
        val adapter = Rule34PahealSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteTags("gensh", limit = 10)

        assertEquals(listOf("genshin_impact", "genshinman"), suggestions.map { it.text })
    }

    @Test
    fun `resolve parses post view html`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAMPLE_PAHEAL_POST_HTML)
        }
        val adapter = Rule34PahealSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.RULE34PAHEAL, sourcePostId = "5773878"))

        assertNotNull(post)
        assertEquals("https://r34i.paheal-cdn.net/20/91/2091a152ad0b5ed6e0edb4bcad404c77", post?.full?.url)
        assertEquals(2685, post?.width)
        assertEquals(1645, post?.height)
        assertEquals(listOf("Neon Genesis Evangelion", "Asuka Langley Sohryu"), post?.canonicalTags)
        assertEquals("https://rule34.paheal.net/post/view/5773878", post?.pageUrl)
    }

    @Test
    fun `trending cancellation is not degraded to an empty list`() = runTest {
        val expected = CancellationException("source changed")
        val adapter = Rule34PahealSourceAdapter(
            httpClient = object : SourceHttpClient {
                override suspend fun get(
                    url: String,
                    query: Map<String, String>,
                    headers: Map<String, String>,
                ): SourceHttpResponse = throw expected

                override suspend fun postForm(
                    url: String,
                    form: Map<String, String>,
                    headers: Map<String, String>,
                ): SourceHttpResponse = error("POST is not used by Rule34Paheal tests")
            },
        )

        var thrown: CancellationException? = null
        try {
            adapter.trendingTags(limit = 2)
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue(thrown === expected)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34PAHEAL),
            includeTags = listOf("genshin_impact"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}

private const val SAMPLE_PAHEAL_RSS = """
<?xml version="1.0" encoding="utf-8" ?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss" xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>Rule 34</title>
    <atom:link rel="next" href="/rss/images/genshin_impact/2" />
    <item>
      <title>7242058 - Genshin_Impact Jean_Gunnhildr TwistedScarlett</title>
      <link>https://rule34.paheal.net/post/view/7242058</link>
      <pubDate>Sat, 07 Mar 2026 05:55:50 +0000</pubDate>
      <description>&lt;div class='shm-thumb thumb' data-ext='png' data-tags='genshin_impact jean_gunnhildr twistedscarlett' data-post-id='7242058'&gt;&lt;img title='Genshin_Impact Jean_Gunnhildr TwistedScarlett
4000x2968 // 4.5MB // png
March 7, 2026; 05:55' src='https://r34t.paheal.net/b9/68/b968fffb55cb42905b457b6f82a2c54a' /&gt;&lt;a href='https://r34i.paheal-cdn.net/b9/68/b968fffb55cb42905b457b6f82a2c54a'&gt;File Only&lt;/a&gt;&lt;/div&gt;</description>
      <media:thumbnail url="https://r34t.paheal.net/b9/68/b968fffb55cb42905b457b6f82a2c54a"/>
      <media:content url="https://r34i.paheal-cdn.net/b9/68/b968fffb55cb42905b457b6f82a2c54a"/>
    </item>
  </channel>
</rss>
"""

private const val SAMPLE_PAHEAL_POST_HTML = """
<html>
  <head><title>Asuka - Rule 34</title></head>
  <body>
    <a class="tag_name" href="/post/list/Neon_Genesis_Evangelion/1">Neon Genesis Evangelion</a>
    <a class="tag_name" href="/post/list/Asuka_Langley_Sohryu/1">Asuka Langley Sohryu</a>
    <section id="Imagemain">
      <div class="blockbody">
        <img class="shm-main-image" id="main_image" src="https://r34i.paheal-cdn.net/20/91/2091a152ad0b5ed6e0edb4bcad404c77" data-width="2685" data-height="1645" data-mime="image/jpeg" />
      </div>
    </section>
  </body>
</html>
"""
