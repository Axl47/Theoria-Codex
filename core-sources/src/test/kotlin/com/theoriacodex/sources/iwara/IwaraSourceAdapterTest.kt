package com.theoriacodex.sources.iwara

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IwaraSourceAdapterTest {
    @Test
    fun `search uses video search endpoint and correct query params`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"page":0,"count":0,"limit":32,"results":[],"type":"videos"}""",
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.IWARA),
                includeTags = listOf("wuthering_waves", "magic"),
                excludeTags = listOf("ignored"),
                sort = SortMode.POPULAR,
                dateRange = null,
                minScore = null,
            ),
            pageToken = "4",
        )

        assertEquals("https://api.iwara.tv/search", httpClient.lastGet?.url)
        assertEquals("videos", httpClient.lastGet?.query?.get("type"))
        assertEquals("4", httpClient.lastGet?.query?.get("page"))
        assertEquals("wuthering_waves magic", httpClient.lastGet?.query?.get("query"))
        assertEquals("views", httpClient.lastGet?.query?.get("sort"))
        assertEquals("https://www.iwara.tv/", httpClient.lastGet?.headers?.get("Referer"))
        assertEquals("Mozilla/5.0", httpClient.lastGet?.headers?.get("User-Agent"))
    }

    @Test
    fun `newest maps to date sort instead of recent`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"page":0,"count":0,"limit":32,"results":[],"type":"videos"}""",
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        adapter.search(sampleQuery(sort = SortMode.NEWEST), pageToken = null)

        assertEquals("date", httpClient.lastGet?.query?.get("sort"))
    }

    @Test
    fun `search maps creator metadata preview fallback and next page token`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "page": 0,
                      "count": 65,
                      "limit": 32,
                      "results": [
                        {
                          "id": "KH2f7fca2MCgZ8",
                          "slug": "wuthering-waves-cartethyiafleurdelys-sex",
                          "title": "Wuwa",
                          "file": {
                            "id": "08ce55ca-b105-4d53-9fd8-56da7de98cf6",
                            "name": "08ce55ca-b105-4d53-9fd8-56da7de98cf6.mp4",
                            "mime": "video/mp4",
                            "path": "2025/05/02"
                          },
                          "customThumbnail": null,
                          "thumbnail": 9,
                          "tags": [
                            {"id": "wuthering_waves", "type": "general", "sensitive": false}
                          ],
                          "user": {
                            "id": "d7678ba5-6039-442b-8ee8-6d8f0cf0398f",
                            "name": "Fearess",
                            "username": "fearess"
                          },
                          "createdAt": "2025-05-02T11:36:03.000Z"
                        }
                      ],
                      "type": "videos"
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(sort = SortMode.TOP), pageToken = null)

        assertEquals("1", page.nextPageToken)
        val post = page.items.single()
        assertEquals(SourceKey.IWARA, post.id.source)
        assertEquals("https://www.iwara.tv/video/KH2f7fca2MCgZ8/wuthering-waves-cartethyiafleurdelys-sex", post.pageUrl)
        assertEquals("https://i.iwara.tv/image/thumbnail/08ce55ca-b105-4d53-9fd8-56da7de98cf6/08ce55ca-b105-4d53-9fd8-56da7de98cf6.jpg", post.preview.url)
        assertNull(post.full)
        assertEquals("Fearess", post.authorName)
        assertEquals("Fearess", post.creatorProfile?.displayName)
        assertEquals("d7678ba5-6039-442b-8ee8-6d8f0cf0398f", post.creatorProfile?.uploadsQuery)
        assertEquals("https://www.iwara.tv/profile/fearess/videos", post.creatorProfile?.profileUrl)
        assertEquals(listOf("wuthering_waves"), post.canonicalTags)
    }

    @Test
    fun `resolve post returns fileUrl backed video post`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": "KH2f7fca2MCgZ8",
                      "slug": "wuthering-waves-cartethyiafleurdelys-sex",
                      "title": "Wuwa",
                      "fileUrl": "https://filesq.iwara.tv/file/08ce55ca-b105-4d53-9fd8-56da7de98cf6?expires=123",
                      "file": {
                        "id": "08ce55ca-b105-4d53-9fd8-56da7de98cf6",
                        "name": "08ce55ca-b105-4d53-9fd8-56da7de98cf6.mp4",
                        "mime": "video/mp4"
                      },
                      "customThumbnail": {
                        "id": "4fb7b3db-44a4-4854-a7b1-1cee5eefb5d8",
                        "name": "4fb7b3db-44a4-4854-a7b1-1cee5eefb5d8.jpeg",
                        "mime": "image/jpeg"
                      },
                      "tags": [],
                      "user": {
                        "id": "d7678ba5-6039-442b-8ee8-6d8f0cf0398f",
                        "name": "Fearess",
                        "username": "fearess"
                      },
                      "createdAt": "2025-05-02T11:36:03.000Z"
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.IWARA, sourcePostId = "KH2f7fca2MCgZ8"))

        assertNotNull(post)
        assertEquals("https://filesq.iwara.tv/file/08ce55ca-b105-4d53-9fd8-56da7de98cf6?expires=123", post?.full?.url)
        assertEquals("video/mp4", post?.full?.mime)
        assertEquals(1, post?.media?.size)
        assertEquals("https://api.iwara.tv/video/KH2f7fca2MCgZ8", httpClient.lastGet?.url)
    }

    @Test
    fun `resolve post returns null for 404`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 404, body = """{"message":"errors.notFound"}""")
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val resolved = adapter.resolvePost(PostId(source = SourceKey.IWARA, sourcePostId = "missing"))

        assertNull(resolved)
    }

    @Test
    fun `creator search uses videos endpoint with user id`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"page":0,"count":0,"limit":32,"results":[]}""",
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        adapter.searchCreatorPosts(
            creator = com.theoriacodex.domain.model.CreatorProfile(
                source = SourceKey.IWARA,
                displayName = "Fearess",
                profileId = "d7678ba5-6039-442b-8ee8-6d8f0cf0398f",
                profileUrl = "https://www.iwara.tv/profile/fearess/videos",
                uploadsQuery = "d7678ba5-6039-442b-8ee8-6d8f0cf0398f",
            ),
            pageToken = "2",
        )

        assertEquals("https://api.iwara.tv/videos", httpClient.lastGet?.url)
        assertEquals("all", httpClient.lastGet?.query?.get("rating"))
        assertEquals("date", httpClient.lastGet?.query?.get("sort"))
        assertEquals("2", httpClient.lastGet?.query?.get("page"))
        assertEquals("d7678ba5-6039-442b-8ee8-6d8f0cf0398f", httpClient.lastGet?.query?.get("user"))
    }

    @Test
    fun `autocomplete tags uses tags endpoint`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "count": 2,
                      "limit": 32,
                      "page": 0,
                      "results": [
                        {"id": "wuwa", "type": "general", "sensitive": false},
                        {"id": "wuthering_waves", "type": "general", "sensitive": false}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteTags(prefix = "wu", limit = 10)

        assertEquals("https://api.iwara.tv/tags", httpClient.lastGet?.url)
        assertEquals("wu", httpClient.lastGet?.query?.get("query"))
        assertEquals(listOf("wuwa", "wuthering_waves"), suggestions.map { it.text })
    }

    @Test
    fun `trending tags aggregates recent feed tag frequencies`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "page": 0,
                      "count": 2,
                      "limit": 32,
                      "results": [
                        {
                          "id": "1",
                          "tags": [
                            {"id": "wuthering_waves", "type": "general", "sensitive": false},
                            {"id": "koikatsu", "type": "category", "sensitive": false}
                          ]
                        },
                        {
                          "id": "2",
                          "tags": [
                            {"id": "wuthering_waves", "type": "general", "sensitive": false}
                          ]
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val tags = adapter.trendingTags(limit = 2)

        assertEquals(listOf("wuthering_waves", "koikatsu"), tags.map { it.text })
        assertEquals(listOf(2, 1), tags.map { it.count })
    }

    @Test
    fun `resolve post preserves embed only entries without crashing`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": "5ak7ohralkswrykwa",
                      "slug": "mmd-genshin-impact-xinyan-sings-god-knows",
                      "title": "Embed only",
                      "embedUrl": "https://www.youtube.com/watch?v=-RvuwgrPDhI",
                      "file": null,
                      "customThumbnail": null,
                      "user": {
                        "id": "9d4df5bd-3947-4db1-a89b-01d57ec45445",
                        "name": "MMDParadaise",
                        "username": "mmdparadaise"
                      },
                      "tags": []
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.IWARA, sourcePostId = "5ak7ohralkswrykwa"))

        assertNotNull(post)
        assertNull(post?.full)
        assertEquals("https://www.iwara.tv/video/5ak7ohralkswrykwa/mmd-genshin-impact-xinyan-sings-god-knows", post?.pageUrl)
    }

    @Test
    fun `http failures map to typed adapter failures`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 429, body = """{"message":"rate limited"}""")
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val failure = runCatching { adapter.search(sampleQuery(sort = SortMode.POPULAR), null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.RATE_LIMITED, failure.reason)
        assertTrue(failure.message?.contains("429") == true)
    }

    private fun sampleQuery(sort: SortMode): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.IWARA),
            includeTags = listOf("wuthering_waves"),
            excludeTags = emptyList(),
            sort = sort,
            dateRange = null,
            minScore = null,
        )
    }
}
