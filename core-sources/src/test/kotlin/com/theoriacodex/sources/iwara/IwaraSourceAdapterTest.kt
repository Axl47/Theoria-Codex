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
    fun `blank search uses videos feed endpoint`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"page":0,"count":0,"limit":32,"results":[]}""",
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.IWARA),
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SortMode.POPULAR,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://api.iwara.tv/videos", httpClient.lastGet?.url)
        assertEquals("all", httpClient.lastGet?.query?.get("rating"))
        assertEquals("views", httpClient.lastGet?.query?.get("sort"))
        assertEquals("0", httpClient.lastGet?.query?.get("page"))
        assertNull(httpClient.lastGet?.query?.get("query"))
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
    fun `search maps creator metadata indexed thumbnail fallback and next page token`() = runTest {
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
                          "fileUrl": "https://filesq.iwara.tv/file/08ce55ca-b105-4d53-9fd8-56da7de98cf6?expires=123",
                          "file": {
                            "id": "08ce55ca-b105-4d53-9fd8-56da7de98cf6",
                            "name": "08ce55ca-b105-4d53-9fd8-56da7de98cf6.mp4",
                            "mime": "video/mp4",
                            "path": "2025/05/02"
                          },
                          "customThumbnail": null,
                          "thumbnail": 9,
                          "duration": 83,
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
        assertEquals("https://i.iwara.tv/image/thumbnail/08ce55ca-b105-4d53-9fd8-56da7de98cf6/thumbnail-09.jpg", post.preview.url)
        assertNull(post.full)
        assertTrue(post.media.isEmpty())
        assertEquals("Fearess", post.authorName)
        assertEquals("Fearess", post.creatorProfile?.displayName)
        assertEquals("d7678ba5-6039-442b-8ee8-6d8f0cf0398f", post.creatorProfile?.uploadsQuery)
        assertEquals("https://www.iwara.tv/profile/fearess/videos", post.creatorProfile?.profileUrl)
        assertEquals(listOf("wuthering_waves"), post.canonicalTags)
        assertEquals(83_000L, post.durationMs)
    }

    @Test
    fun `search excludes embed only posts without native iwara media`() = runTest {
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
                          "id": "keep-me",
                          "slug": "native-video",
                          "title": "Native",
                          "thumbnail": 3,
                          "file": {
                            "id": "3c30efe5-744d-47e7-b691-b963af08a792",
                            "name": "3c30efe5-744d-47e7-b691-b963af08a792.mp4",
                            "mime": "video/mp4"
                          },
                          "user": {
                            "id": "user-1",
                            "name": "Uploader",
                            "username": "uploader"
                          },
                          "tags": []
                        },
                        {
                          "id": "drop-me",
                          "slug": "youtube-embed",
                          "title": "Embed",
                          "embedUrl": "https://www.youtube.com/watch?v=-RvuwgrPDhI",
                          "file": null,
                          "customThumbnail": null,
                          "user": {
                            "id": "user-2",
                            "name": "Embedder",
                            "username": "embedder"
                          },
                          "tags": []
                        }
                      ],
                      "type": "videos"
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(sort = SortMode.NEWEST), pageToken = null)

        assertEquals(1, page.items.size)
        assertEquals("keep-me", page.items.single().id.sourcePostId)
    }

    @Test
    fun `resolve post returns fileUrl backed video post`() = runTest {
        val httpClient = object : com.theoriacodex.sources.http.SourceHttpClient {
            var callCount = 0
            var lastUrl: String? = null

            override suspend fun get(
                url: String,
                query: Map<String, String>,
                headers: Map<String, String>,
            ): SourceHttpResponse {
                callCount += 1
                lastUrl = url
                return when (callCount) {
                    1 -> SourceHttpResponse(
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
                    else -> SourceHttpResponse(
                        statusCode = 200,
                        body = """
                            [
                              {
                                "name": "preview",
                                "type": "video/mp4",
                                "src": {
                                  "view": "//bronya.iwara.tv/view?filename=preview.mp4",
                                  "download": "//bronya.iwara.tv/download?filename=preview.mp4"
                                }
                              },
                              {
                                "name": "720",
                                "type": "video/mp4",
                                "src": {
                                  "view": "//mikoto.iwara.tv/view?filename=video_720.mp4",
                                  "download": "//mikoto.iwara.tv/download?filename=video_720.mp4"
                                }
                              }
                            ]
                        """.trimIndent(),
                    )
                }
            }

            override suspend fun postForm(
                url: String,
                form: Map<String, String>,
                headers: Map<String, String>,
            ): SourceHttpResponse {
                error("unused")
            }
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.IWARA, sourcePostId = "KH2f7fca2MCgZ8"))

        assertNotNull(post)
        assertEquals("https://mikoto.iwara.tv/view?filename=video_720.mp4", post?.full?.url)
        assertEquals("video/mp4", post?.full?.mime)
        assertEquals(1, post?.media?.size)
        assertEquals("https://filesq.iwara.tv/file/08ce55ca-b105-4d53-9fd8-56da7de98cf6?expires=123", httpClient.lastUrl)
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
    fun `autocomplete tags filters provider response before limiting`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "count": 4,
                      "limit": 32,
                      "page": 0,
                      "results": [
                        {"id": "1080p", "type": "general", "sensitive": false},
                        {"id": "120fps", "type": "general", "sensitive": false},
                        {"id": "3ds_max", "type": "general", "sensitive": false},
                        {"id": "3d_custom_girl", "type": "source", "sensitive": false}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteTags(prefix = "3d", limit = 2)

        assertEquals(listOf("3ds_max", "3d_custom_girl"), suggestions.map { it.text })
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
    fun `resolve post returns null for embed only entries without native iwara media`() = runTest {
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

        assertNull(post)
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

    @Test
    fun `cloudflare challenge falls back to jina mirror`() = runTest {
        val httpClient = object : com.theoriacodex.sources.http.SourceHttpClient {
            val requests = mutableListOf<Pair<String, Map<String, String>>>()

            override suspend fun get(
                url: String,
                query: Map<String, String>,
                headers: Map<String, String>,
            ): SourceHttpResponse {
                requests += url to query
                return when (requests.size) {
                    1 -> SourceHttpResponse(
                        statusCode = 403,
                        body = "<html>cloudflare challenge</html>",
                        headers = mapOf("cf-mitigated" to listOf("challenge")),
                    )
                    else -> SourceHttpResponse(
                        statusCode = 200,
                        body = """
                            Title:

                            URL Source: https://api.iwara.tv/search?type=videos&page=0&query=wuthering_waves&sort=views

                            Markdown Content:
                            {"page":0,"count":0,"limit":32,"results":[],"type":"videos"}
                        """.trimIndent(),
                    )
                }
            }

            override suspend fun postForm(
                url: String,
                form: Map<String, String>,
                headers: Map<String, String>,
            ): SourceHttpResponse {
                error("unused")
            }
        }
        val adapter = IwaraSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(sort = SortMode.POPULAR), pageToken = null)

        assertTrue(page.items.isEmpty())
        assertEquals(2, httpClient.requests.size)
        assertEquals("https://api.iwara.tv/search", httpClient.requests.first().first)
        assertTrue(
            httpClient.requests.last().first.startsWith(
                "https://r.jina.ai/http://https://api.iwara.tv/search%3F",
            ),
        )
        assertTrue(
            "page=0 must be encoded so Jina does not treat it as its own page parameter",
            "page%3D0" in httpClient.requests.last().first,
        )
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
