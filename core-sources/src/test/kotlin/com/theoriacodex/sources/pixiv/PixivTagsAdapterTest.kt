package com.theoriacodex.sources.pixiv

import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pixiv tag handling: native tags, English translations and search tokens. */
class PixivTagsAdapterTest {
    @Test
    fun `autocomplete and trending preserve native tags with translated labels`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"tags":[{"name":"初音ミク","translated_name":"Hatsune Miku"}]}""",
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
            minRequestIntervalMs = 0L,
        )

        val autocomplete = adapter.autocompleteTags("hatsune", limit = 10).single()
        assertEquals("初音ミク", autocomplete.text)
        assertEquals("Hatsune Miku", autocomplete.alternateText)
        assertEquals("en-US", httpClient.lastGet?.headers?.get("Accept-Language"))

        httpClient.nextGetResponse = SourceHttpResponse(
            statusCode = 200,
            body = """{"trend_tags":[{"tag":{"name":"風景","translated_name":"Scenery"}}]}""",
        )
        val trending = adapter.trendingTags(limit = 10).single()
        assertEquals("風景", trending.text)
        assertEquals("Scenery", trending.alternateText)
    }


    @Test
    fun `search normalizes booru-style tags but keeps native pixiv tags intact`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": []
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

        adapter.search(
            Query(
                mode = QueryMode.Source(SourceKey.PIXIV),
                includeTags = listOf("this_is_a_tag_(Game)", "ラム(リゼロ)"),
                excludeTags = listOf("nsfw_(content)"),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("this is a tag ラム(リゼロ) -nsfw", httpClient.lastGet?.query?.get("word"))
    }

    @Test
    fun `search pairs each native tag with its English translation`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            pixivTokens = PixivAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                expiresAtEpochMs = Long.MAX_VALUE,
            )
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "illusts": [
                        {
                          "id": 7,
                          "image_urls": {"large": "https://i.pximg.net/lg.jpg"},
                          "tags": [
                            {"name": "初音ミク", "translated_name": "Hatsune Miku"},
                            {"name": "オリジナル", "translated_name": null},
                            {"name": "VOCALOID", "translated_name": "vocaloid"}
                          ]
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = PixivSourceAdapter(httpClient = httpClient, credentialsProvider = credentials)

        val post = adapter.search(sampleQuery(), pageToken = null).items.single()

        assertEquals(listOf("初音ミク", "オリジナル", "VOCALOID"), post.rawTags)
        assertEquals(listOf("初音ミク", "Hatsune Miku", "オリジナル", "VOCALOID"), post.canonicalTags)
        assertEquals(
            listOf(
                PostTaxonomyTerm("初音ミク", translation = "Hatsune Miku"),
                PostTaxonomyTerm("オリジナル"),
                PostTaxonomyTerm("VOCALOID"),
            ),
            post.taxonomy,
        )
        assertEquals("en-US", httpClient.lastGet?.headers?.get("Accept-Language"))
    }


    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.PIXIV),
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }
}
