package com.theoriacodex.sources

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealProviderPageContractTest {
    @Test
    fun `real provider pages deduplicate canonical identities after parsing without losing continuation or order`() = runTest {
        for (fixture in fixtures()) {
            val adapter = registry(fixture).adapterFor(fixture.source)!!
            val page = adapter.search(Query(
                mode = QueryMode.Source(fixture.source), includeTags = emptyList(), excludeTags = emptyList(),
                sort = SortMode.NEWEST, dateRange = null, minScore = null,
            ), null)
            assertEquals(fixture.source.name, listOf("42", "43"), page.items.map { it.id.sourcePostId })
            assertEquals(fixture.source.name, fixture.continuation, page.nextPageToken)
            assertEquals(fixture.source.name, setOf(fixture.source), page.items.map { it.id.source }.toSet())
        }
    }

    @Test
    fun `creator pages retain canonical uniqueness and authoritative continuation`() = runTest {
        for (fixture in fixtures().filter { it.source in setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.IWARA) }) {
            val adapter = registry(fixture).adapterFor(fixture.source) as CreatorPostsSourceAdapter
            val page = adapter.searchCreatorPosts(CreatorProfile(
                source = fixture.source, displayName = "Creator", profileId = "7", uploadsQuery = "7", profileUrl = null,
            ), null)
            assertEquals(fixture.source.name, listOf("42", "43"), page.items.map { it.id.sourcePostId })
            assertEquals(fixture.source.name, fixture.continuation, page.nextPageToken)
        }
    }

    private fun registry(fixture: Fixture) = RealAdapterRegistry(
        credentialsProvider = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens("fixture-access", "fixture-refresh", Long.MAX_VALUE)
            rule34XxxCredentials = Rule34XxxCredentials("fixture-user", "fixture-key")
        },
        httpClient = FakeHttpClient().apply { nextGetResponse = SourceHttpResponse(200, fixture.body) },
        exposedSources = setOf(fixture.source),
    )

    // Deliberately synthetic protocol edge cases. Hitomi's binary index and post-hydration alias
    // contracts remain in HitomiGlobalIndexSourceAdapterTest, with its captured protocol fixtures.
    private fun fixtures(): List<Fixture> {
        val booru = (List(39) { index ->
            """{"id":${if (index % 2 == 0) "42" else "\"42\""},"file_url":"https://example.com/42.png","tags":"sky"}"""
        } + """{"id":43,"file_url":"https://example.com/43.png","tags":"sky"}""").joinToString(",", "[", "]")
        val pixiv = """{"illusts":[{"id":42},{"id":"42"},{"id":43}],"next_url":"https://app-api.pixiv.net/v1/search/illust?offset=30"}"""
        val nhentai = """{"result":[{"id":42,"media_id":100},{"id":"42","media_id":100},{"id":43,"media_id":101}],"num_pages":2}"""
        val iwara = """{"results":[{"id":"42"},{"id":" 42 "},{"id":"43"}],"page":0,"limit":3,"count":6}"""
        val rssItems = listOf(42, 42, 43).joinToString("") { id ->
            "<item><link>https://example.com/video/$id/title/</link><title>Clip $id</title></item>"
        }
        val pahealItems = listOf("42", "42#sample", "43").joinToString("") { id ->
            "<item><link>https://rule34.paheal.net/post/view/$id</link><title>Post</title></item>"
        }
        return listOf(
            Fixture(SourceKey.AIBOORU, booru, "2"),
            Fixture(SourceKey.GELBOORU, booru, "1"),
            Fixture(SourceKey.RULE34XXX, booru, "1"),
            Fixture(SourceKey.PIXIV, pixiv, "30"),
            Fixture(SourceKey.NHENTAI, nhentai, "2"),
            Fixture(SourceKey.IWARA, iwara, "1"),
            Fixture(SourceKey.RULE34VIDEO, "<rss><channel>$rssItems</channel></rss>", null),
            Fixture(SourceKey.RULE34GEN, "<rss><channel>$rssItems</channel></rss>", null),
            Fixture(SourceKey.RULE34PAHEAL, "<rss><channel>$pahealItems</channel></rss>", null),
        )
    }

    private data class Fixture(val source: SourceKey, val body: String, val continuation: String?)
}
