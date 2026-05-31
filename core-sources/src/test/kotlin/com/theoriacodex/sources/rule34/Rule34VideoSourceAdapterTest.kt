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

class Rule34VideoSourceAdapterTest {
    @Test
    fun `search parses result cards and next token`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAMPLE_RULE34VIDEO_SEARCH_HTML)
        }
        val adapter = Rule34VideoSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals("https://rule34video.com/search/genshin/", httpClient.lastGet?.url)
        assertEquals("3089604", page.items.first().id.sourcePostId)
        assertEquals("https://rule34video.com/contents/videos_screenshots/3089000/3089604/320x180/1.jpg", page.items.first().preview.url)
        assertEquals(65_000L, page.items.first().durationMs)
        assertTrue(page.nextPageToken?.contains("mode=async") == true)
        assertTrue(page.nextPageToken?.contains("from_videos=2") == true)
        assertTrue(page.nextPageToken?.contains("from_albums=2") == true)
    }

    @Test
    fun `resolve extracts best mp4 preview tags and canonical url`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAMPLE_RULE34VIDEO_POST_HTML)
        }
        val adapter = Rule34VideoSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.RULE34VIDEO, sourcePostId = "3089604"))

        assertEquals("https://rule34video.com/video/3089604/gen-gen-gen-hmv-pmv-genshin-impact/", post?.pageUrl)
        assertEquals("https://rule34video.com/contents/videos_screenshots/3089000/3089604/preview.jpg", post?.preview?.url)
        assertEquals("https://rule34video.com/get_file/47/cb2f4505271f422a10a1244243bba9fa/3089000/3089604/3089604_1080p.mp4/", post?.full?.url)
        assertEquals("video/mp4", post?.media?.firstOrNull()?.mime)
        assertEquals(92_000L, post?.durationMs)
        assertTrue(post?.canonicalTags?.contains("genshin impact") == true)
        assertTrue(post?.canonicalTags?.contains("HorizontalSlope") == true)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34VIDEO),
            includeTags = listOf("genshin"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}

private const val SAMPLE_RULE34VIDEO_SEARCH_HTML = """
<html>
  <body>
    <div class="item thumb video_1">
      <a class="th" href="https://rule34video.com/video/3089604/gen-gen-gen-hmv-pmv-genshin-impact/" title="GEN GEN GEN! HMV/PMV Genshin Impact">
        <img class="thumb" data-original="https://rule34video.com/contents/videos_screenshots/3089000/3089604/320x180/1.jpg" data-preview="https://rule34video.com/get_file/47/preview.mp4/" />
        <span class="duration">1:05</span>
      </a>
    </div>
    <div class="pager next">
      <a data-block-id="custom_list_videos_videos_list_search" data-parameters="q:genshin;sort_by:;from_videos+from_albums:2"></a>
    </div>
  </body>
</html>
"""

private const val SAMPLE_RULE34VIDEO_POST_HTML = """
<html>
  <head>
    <link rel="canonical" href="https://rule34video.com/video/3089604/gen-gen-gen-hmv-pmv-genshin-impact/" />
  </head>
  <body>
    <script>
      var tee22a13254 = {
        video_id: '3089604',
        video_title: 'GEN GEN GEN! HMV/PMV Genshin Impact',
        video_categories: 'Genshin Impact, 3D',
        video_tags: 'lumine (genshin impact), pmv, genshin impact',
        video_models: 'HorizontalSlope, Bewyx',
        video_duration: '1:32',
        video_url: 'https://rule34video.com/get_file/47/b192efbb159aca85ebc567507bad6926/3089000/3089604/3089604_360.mp4/',
        video_url_text: '360p',
        video_alt_url: 'https://rule34video.com/get_file/47/4d3b9bd4be4a22bd6e79fc2bc5b4220f/3089000/3089604/3089604_480p.mp4/',
        video_alt_url_text: '480p',
        video_alt_url2: 'https://rule34video.com/get_file/47/05334d7e2826bca73b56a8b9c2905326/3089000/3089604/3089604_720p.mp4/',
        video_alt_url2_text: '720p',
        video_alt_url3: 'https://rule34video.com/get_file/47/cb2f4505271f422a10a1244243bba9fa/3089000/3089604/3089604_1080p.mp4/',
        video_alt_url3_text: '1080p',
        preview_url: 'https://rule34video.com/contents/videos_screenshots/3089000/3089604/preview.jpg'
      };
      window['player_obj'] = kt_player('kt_player', 'https://rule34video.com/player/kt_player.swf', '100%', '100%', tee22a13254);
    </script>
  </body>
</html>
"""
