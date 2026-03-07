package com.theoriacodex.sources.rule34

import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rule34GenSourceAdapterTest {
    @Test
    fun `search parses cards and next page url`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAMPLE_RULE34GEN_SEARCH_HTML)
        }
        val adapter = Rule34GenSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals("https://rule34gen.com/search/genshin/", httpClient.lastGet?.url)
        assertEquals("8255", page.items.first().id.sourcePostId)
        assertEquals("https://rule34gen.com/search/genshin/2/", page.nextPageToken)
    }

    @Test
    fun `resolve extracts direct mp4 and tags`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAMPLE_RULE34GEN_POST_HTML)
        }
        val adapter = Rule34GenSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.RULE34GEN, sourcePostId = "8255"))

        assertEquals("https://rule34gen.com/video/8255/claire-russell-futa-cumshot-kiyuxaai/", post?.pageUrl)
        assertEquals("https://rule34gen.com/contents/videos_screenshots/8000/8255/preview.jpg", post?.preview?.url)
        assertEquals("https://rule34gen.com/get_file/3/0e3f96a19803858eb0c8782ad84e8a60/8000/8255/8255_480p.mp4/", post?.full?.url)
        assertTrue(post?.canonicalTags?.contains("cumshot") == true)
        assertTrue(post?.canonicalTags?.contains("3d") == true)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34GEN),
            includeTags = listOf("genshin"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}

private const val SAMPLE_RULE34GEN_SEARCH_HTML = """
<html>
  <body>
    <div class="cards" data-block="ajax" data-block-id="list_videos_common_videos_list" data-block-next="https://rule34gen.com/search/genshin/2/">
      <div class="cards__item" data-item-id="8255">
        <a href="https://rule34gen.com/video/8255/claire-russell-futa-cumshot-kiyuxaai/" data-ajax="video" class="card" title="Claire Russell Futa Cumshot - KiyuxaAI">
          <img data-original="https://rule34gen.com/contents/videos_screenshots/8000/8255/496x372/1.jpg" data-preview="https://rule34gen.com/get_file/3/preview.mp4/" class="card__image" />
        </a>
      </div>
    </div>
  </body>
</html>
"""

private const val SAMPLE_RULE34GEN_POST_HTML = """
<html>
  <head>
    <link rel="canonical" href="https://rule34gen.com/video/8255/claire-russell-futa-cumshot-kiyuxaai/" />
  </head>
  <body>
    <script>
      var tee22a13254 = {
        video_id: '8255',
        video_title: 'Claire Russell Futa Cumshot - KiyuxaAI',
        video_categories: '3d',
        video_tags: 'futanari, cum, cumshot',
        video_models: '',
        video_url: 'https://rule34gen.com/get_file/3/1611ab6e92db2e00601411bb656ffd07/8000/8255/8255_360.mp4/',
        video_url_text: '360p',
        video_alt_url: 'https://rule34gen.com/get_file/3/0e3f96a19803858eb0c8782ad84e8a60/8000/8255/8255_480p.mp4/',
        video_alt_url_text: '480p',
        preview_url: 'https://rule34gen.com/contents/videos_screenshots/8000/8255/preview.jpg'
      };
      window['player_obj'] = kt_player('kt_player', 'https://rule34gen.com/player/kt_player.swf', '100%', '100%', tee22a13254);
    </script>
  </body>
</html>
"""
