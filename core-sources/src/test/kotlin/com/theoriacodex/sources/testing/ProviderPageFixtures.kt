package com.theoriacodex.sources.testing

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.Rule34XxxCredentials

/** One provider's search page: posts 42 (listed twice) and 43, then [continuation]. */
internal data class ProviderPageFixture(val source: SourceKey, val body: String, val continuation: String?)

/** Credentials every registry source accepts without refreshing. */
internal fun providerPageCredentials() = FakeCredentialsProvider().apply {
    pixivTokens = PixivAuthTokens("fixture-access", "fixture-refresh", Long.MAX_VALUE)
    rule34XxxCredentials = Rule34XxxCredentials("fixture-user", "fixture-key")
}

internal fun providerPageQuery(source: SourceKey) = Query(
    mode = QueryMode.Source(source), includeTags = emptyList(), excludeTags = emptyList(),
    sort = SortMode.NEWEST, dateRange = null, minScore = null,
)

/**
 * A search page for every registry source except Hitomi, whose binary index and post-hydration alias contracts
 * remain in HitomiGlobalIndexSourceAdapterTest with its captured protocol fixtures. Deliberately synthetic
 * protocol edge cases.
 */
internal fun providerPageFixtures(): List<ProviderPageFixture> {
    val booru = (List(39) { index ->
        """{"id":${if (index % 2 == 0) "42" else "\"42\""},"file_url":"https://example.com/42.png","tags":"sky"}"""
    } + """{"id":43,"file_url":"https://example.com/43.png","tags":"sky"}""").joinToString(",", "[", "]")
    val pixiv = """{"illusts":[{"id":42},{"id":"42"},{"id":43}],"next_url":"https://app-api.pixiv.net/v1/search/illust?offset=30"}"""
    val nhentai = """{"result":[{"id":42,"media_id":100},{"id":"42","media_id":100},{"id":43,"media_id":101}],"num_pages":2}"""
    val iwara = """{"results":[{"id":"42"},{"id":" 42 "},{"id":"43"}],"page":0,"limit":3,"count":6}"""
    val rssItems = listOf(42, 42, 43).joinToString("") { id ->
        "<item><link>https://example.com/video/$id/title/</link><title>Clip $id</title></item>"
    }
    val listingCards = listOf(42, 42, 43).joinToString("") { id ->
        """<div class="item thumb"><a class="th" href="https://example.com/video/$id/title/"><img src="https://example.com/$id.jpg"></a></div>"""
    }
    val pahealItems = listOf("42", "42#sample", "43").joinToString("") { id ->
        "<item><link>https://rule34.paheal.net/post/view/$id</link><title>Post</title></item>"
    }
    return listOf(
        // Danbooru sites page newest-first searches before the lowest post id.
        ProviderPageFixture(SourceKey.AIBOORU, booru, "b42"),
        ProviderPageFixture(SourceKey.DANBOORU, booru, "b42"),
        ProviderPageFixture(SourceKey.GELBOORU, booru, "1"),
        ProviderPageFixture(SourceKey.RULE34XXX, booru, "1"),
        ProviderPageFixture(SourceKey.PIXIV, pixiv, "30"),
        ProviderPageFixture(SourceKey.NHENTAI, nhentai, "2"),
        ProviderPageFixture(SourceKey.IWARA, iwara, "1"),
        ProviderPageFixture(SourceKey.RULE34VIDEO, "<rss><channel>$rssItems</channel></rss>", null),
        ProviderPageFixture(SourceKey.RULE34GEN, "<html><body>$listingCards</body></html>", null),
        ProviderPageFixture(SourceKey.RULE34PAHEAL, "<rss><channel>$pahealItems</channel></rss>", null),
    )
}
