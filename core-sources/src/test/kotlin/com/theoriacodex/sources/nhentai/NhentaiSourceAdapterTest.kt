package com.theoriacodex.sources.nhentai

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NhentaiSourceAdapterTest {
    @Test
    fun `search uses galleries search endpoint and query sort params`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [],
                      "num_pages": 1,
                      "per_page": 25
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("big_breasts"),
                excludeTags = listOf("loli"),
                sort = SortMode.POPULAR,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/search", httpClient.lastGet?.url)
        assertEquals("1", httpClient.lastGet?.query?.get("page"))
        assertEquals("big breasts -loli", httpClient.lastGet?.query?.get("query"))
        assertEquals("popular-today", httpClient.lastGet?.query?.get("sort"))
        assertEquals("TheoriaCodex/1.0 (Android source adapter)", httpClient.lastGet?.headers?.get("User-Agent"))
        assertEquals("https://nhentai.net/", httpClient.lastGet?.headers?.get("Referer"))
    }

    @Test
    fun `search compiles typed app facets to nhentai namespaces`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"result":[],"num_pages":1,"per_page":25}""",
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)
        val query = Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTerms = listOf(
                SearchTerm("najar", SearchFacet.ARTIST, "artist"),
                SearchTerm("the idolmaster", SearchFacet.SERIES, "parody"),
                SearchTerm("artistcg", SearchFacet.TYPE, "category"),
                SearchTerm("english", SearchFacet.LANGUAGE, "language"),
                SearchTerm("full color", SearchFacet.TAG, "tag"),
            ),
            excludeTerms = listOf(
                SearchTerm("rin", SearchFacet.CHARACTER, "character"),
            ),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        adapter.search(query = query, pageToken = null)

        assertEquals(
            "artist:najar parody:\"the idolmaster\" category:artistcg " +
                "language:english tag:\"full color\" -character:rin",
            httpClient.lastGet?.query?.get("query"),
        )
    }

    @Test
    fun `blank popular search uses v2 wildcard query`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [],
                      "num_pages": 1,
                      "per_page": 25
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = emptyList(),
                excludeTags = emptyList(),
                sort = SortMode.POPULAR,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/search", httpClient.lastGet?.url)
        assertEquals("*", httpClient.lastGet?.query?.get("query"))
        assertEquals("popular-today", httpClient.lastGet?.query?.get("sort"))
    }

    @Test
    fun `search maps gallery pages and next token`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [
                        {
                          "id": 123,
                          "media_id": 9876,
                          "title": {
                            "english": "",
                            "japanese": "",
                            "pretty": "Test Gallery"
                          },
                          "images": {
                            "thumbnail": {"t": "w", "w": 350, "h": 500},
                            "cover": {"t": "w", "w": 700, "h": 1000},
                            "pages": [
                              {"t": "w", "w": 1200, "h": 1800},
                              {"t": "g", "w": 1200, "h": 1800}
                            ]
                          },
                          "scanlator": null,
                          "upload_date": 1710000000,
                          "tags": [
                            {"id": 1, "type": "tag", "name": "big breasts", "count": 5000}
                          ],
                          "num_pages": 2,
                          "num_favorites": 150
                        }
                      ],
                      "num_pages": 2,
                      "per_page": 25
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(multiTagQuery(), pageToken = null)

        assertEquals("2", page.nextPageToken)
        val post = page.items.firstOrNull()
        assertNotNull(post)
        assertEquals(SourceKey.NHENTAI, post?.id?.source)
        assertEquals("https://t.nhentai.net/galleries/9876/thumb.webp", post?.preview?.url)
        assertEquals(2, post?.media?.size)
        assertEquals("https://i.nhentai.net/galleries/9876/1.webp", post?.media?.get(0)?.url)
        assertEquals("https://i.nhentai.net/galleries/9876/2.gif", post?.media?.get(1)?.url)
        assertEquals("https://nhentai.net/g/123/", post?.pageUrl)
        assertEquals(listOf("big breasts"), post?.canonicalTags)
        assertEquals(
            listOf(Triple(SearchFacet.TAG, "tag", "big breasts")),
            post?.taxonomy?.map { term -> Triple(term.facet, term.sourceNamespace, term.value) },
        )
        assertEquals(2, post?.mediaCount)
    }

    @Test
    fun `search maps v2 lightweight gallery cards as lazy posts`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [
                        {
                          "id": 659203,
                          "media_id": "4009462",
                          "english_title": "[Osuwaani] Commission",
                          "japanese_title": null,
                          "thumbnail": "galleries/4009462/thumb.webp",
                          "thumbnail_width": 250,
                          "thumbnail_height": 348,
                          "num_pages": 4,
                          "num_favorites": 69,
                          "tag_ids": []
                        }
                      ],
                      "num_pages": 2,
                      "per_page": 25,
                      "total": 26
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(multiTagQuery(), pageToken = null)

        assertEquals("2", page.nextPageToken)
        val post = page.items.single()
        assertEquals("659203", post.id.sourcePostId)
        assertEquals("[Osuwaani] Commission", post.title)
        assertEquals("https://t.nhentai.net/galleries/4009462/thumb.webp", post.preview.url)
        assertEquals("image/webp", post.preview.mime)
        assertNull(post.full)
        assertTrue(post.media.isEmpty())
        assertEquals(4, post.mediaCount)
    }

    @Test
    fun `resolve post returns null for not found gallery`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 404, body = "{}")
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val resolved = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "123456"))

        assertNull(resolved)
    }

    @Test
    fun `search maps blocked html response to network failure`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = "<html><body>blocked</body></html>",
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val failure = runCatching { adapter.search(multiTagQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.NETWORK, failure.reason)
        assertTrue(failure.message?.contains("non-JSON", ignoreCase = true) == true)
    }

    @Test
    fun `search resolves direct gallery id without search endpoint`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 634609,
                      "media_id": 3822885,
                      "title": {
                        "pretty": "Direct ID"
                      },
                      "images": {
                        "thumbnail": {"t": "w", "w": 250, "h": 360},
                        "cover": {"t": "w", "w": 700, "h": 1000},
                        "pages": [
                          {"t": "w", "w": 1200, "h": 1800}
                        ]
                      },
                      "tags": [],
                      "upload_date": 1710000000
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("english", "634609"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.lastGet?.url)
        assertTrue(httpClient.lastGet?.query?.isEmpty() == true)
        assertEquals(1, page.items.size)
        assertEquals("634609", page.items.first().id.sourcePostId)
    }

    @Test
    fun `direct gallery id lookup ignores nhentai filter tags`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 634609,
                      "media_id": 3822885,
                      "title": {"pretty": "Direct ID"},
                      "images": {
                        "thumbnail": {"t": "w"},
                        "cover": {"t": "w"},
                        "pages": [{"t": "w"}]
                      },
                      "tags": []
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(
            query = Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("english", "full color", "634609"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.lastGet?.url)
        assertEquals(1, page.items.size)
    }

    @Test
    fun `single tag search resolves tag id and uses tagged galleries endpoint`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {
                        "id": 2937,
                        "type": "tag",
                        "name": "big breasts",
                        "slug": "big-breasts",
                        "url": "/tag/big-breasts/",
                        "count": 224436
                      }
                    ]
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [],
                      "num_pages": 1,
                      "per_page": 25
                    }
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        adapter.search(sampleQuery(), pageToken = null)

        assertEquals("https://nhentai.net/api/v2/tags/search", httpClient.postRequests.single().url)
        assertEquals("""{"query":"big breasts"}""", httpClient.postRequests.single().body)
        assertEquals("https://nhentai.net/api/v2/galleries/tagged", httpClient.requests.single().url)
        assertEquals("2937", httpClient.requests.single().query["tag_id"])
        assertEquals("date", httpClient.requests.single().query["sort"])
    }

    @Test
    fun `autocomplete tags uses v2 tag search endpoint`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {
                        "id": 2937,
                        "type": "tag",
                        "name": "big breasts",
                        "slug": "big-breasts",
                        "url": "/tag/big-breasts/",
                        "count": 224436
                      },
                      {
                        "id": 30555,
                        "type": "tag",
                        "name": "big penis",
                        "slug": "big-penis",
                        "url": "/tag/big-penis/",
                        "count": 32214
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteTags(prefix = "big", limit = 1)

        assertEquals("https://nhentai.net/api/v2/tags/search", httpClient.lastPost?.url)
        assertEquals("""{"query":"big"}""", httpClient.lastPost?.body)
        assertEquals("application/json", httpClient.lastPost?.headers?.get("Content-Type"))
        assertEquals(listOf("big breasts"), suggestions.map { it.text })
        assertEquals(listOf(224436), suggestions.map { it.count })
    }

    @Test
    fun `faceted autocomplete exposes app scopes and preserves nhentai taxonomy`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"id": 1, "type": "tag", "name": "najar", "slug": "najar", "count": 10},
                      {"id": 2, "type": "artist", "name": "najar", "slug": "najar", "count": 42},
                      {"id": 3, "type": "parody", "name": "the idolmaster", "slug": "the-idolmaster", "count": 99},
                      {"id": 4, "type": "category", "name": "artistcg", "slug": "artistcg", "count": 7}
                    ]
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        val suggestions = adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope.All,
            limit = 10,
        )
        val tagSuggestions = adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope(SearchFacet.TAG, "tag"),
            limit = 10,
        )
        val artistSuggestions = adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            limit = 10,
        )

        assertTrue(FacetedSearchScope.All in adapter.supportedSearchScopes)
        assertTrue(FacetedSearchScope(SearchFacet.SERIES, "parody") in adapter.supportedSearchScopes)
        assertTrue(FacetedSearchScope(SearchFacet.TYPE, "category") in adapter.supportedSearchScopes)
        assertEquals(
            listOf(
                Triple(SearchFacet.TAG, "tag", "najar"),
                Triple(SearchFacet.ARTIST, "artist", "najar"),
                Triple(SearchFacet.SERIES, "parody", "the idolmaster"),
                Triple(SearchFacet.TYPE, "category", "artistcg"),
            ),
            suggestions.map { suggestion ->
                Triple(suggestion.facet, suggestion.sourceNamespace, suggestion.text)
            },
        )
        assertEquals(listOf(10, 42, 99, 7), suggestions.map { suggestion -> suggestion.count })
        assertEquals(listOf("najar"), tagSuggestions.map { suggestion -> suggestion.text })
        assertEquals(listOf("najar"), artistSuggestions.map { suggestion -> suggestion.text })
        assertEquals(SearchFacet.TAG, tagSuggestions.single().facet)
        assertEquals(SearchFacet.ARTIST, artistSuggestions.single().facet)
    }

    @Test
    fun `faceted autocomplete filters one provider response to the selected scope`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextPostResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"id": 1, "type": "tag", "name": "sample", "slug": "sample", "count": 10},
                      {"id": 2, "type": "artist", "name": "sample artist", "slug": "sample-artist", "count": 42}
                    ]
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val suggestions = adapter.autocompleteFaceted(
            prefix = "sample",
            scope = FacetedSearchScope(SearchFacet.ARTIST, "artist"),
            limit = 10,
        )

        assertEquals(listOf("sample artist"), suggestions.map { suggestion -> suggestion.text })
        assertEquals("""{"query":"sample"}""", httpClient.lastPost?.body)
    }

    @Test
    fun `same-name faceted cache resolves a portable search with the tag id`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"id": 11, "type": "tag", "name": "najar", "slug": "najar", "count": 10},
                      {"id": 22, "type": "artist", "name": "najar", "slug": "najar", "count": 42}
                    ]
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """{"result":[],"num_pages":1,"per_page":25}""",
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        adapter.autocompleteFaceted(
            prefix = "najar",
            scope = FacetedSearchScope.All,
            limit = 10,
        )
        adapter.search(
            Query(
                mode = QueryMode.Source(SourceKey.NHENTAI),
                includeTags = listOf("najar"),
                excludeTags = emptyList(),
                sort = SortMode.NEWEST,
                dateRange = null,
                minScore = null,
            ),
            pageToken = null,
        )

        assertEquals(1, httpClient.postRequests.size)
        assertEquals("https://nhentai.net/api/v2/galleries/tagged", httpClient.requests.single().url)
        assertEquals("11", httpClient.requests.single().query["tag_id"])
    }

    @Test
    fun `trending tags samples gallery details to rank tag objects`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "result": [
                        {"id": 101, "tag_ids": [1, 2]},
                        {"id": 102, "tag_ids": [1, 3]}
                      ],
                      "num_pages": 1
                    }
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 101,
                      "tags": [
                        {"id": 1, "type": "language", "name": "english", "slug": "english", "count": 141927},
                        {"id": 2, "type": "tag", "name": "big breasts", "slug": "big-breasts", "count": 224436}
                      ]
                    }
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 102,
                      "tags": [
                        {"id": 1, "type": "language", "name": "english", "slug": "english", "count": 141927},
                        {"id": 3, "type": "category", "name": "doujinshi", "slug": "doujinshi", "count": 490863}
                      ]
                    }
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        val suggestions = adapter.trendingTags(limit = 3)

        assertEquals(
            listOf(
                "https://nhentai.net/api/v2/galleries",
                "https://nhentai.net/api/v2/galleries/101",
                "https://nhentai.net/api/v2/galleries/102",
            ),
            httpClient.requests.map { it.url },
        )
        assertEquals(listOf("doujinshi", "big breasts", "english"), suggestions.map { it.text })
    }

    @Test
    fun `resolve post maps v2 gallery details with page paths`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": 634609,
                      "media_id": "3821534",
                      "title": {
                        "english": "English Detail",
                        "japanese": null,
                        "pretty": "Pretty Detail"
                      },
                      "cover": {"path": "galleries/3821534/cover.webp.webp", "width": 350, "height": 494},
                      "thumbnail": {"path": "galleries/3821534/thumb.webp", "width": 250, "height": 353},
                      "scanlator": "",
                      "upload_date": 1772669054,
                      "tags": [
                        {"id": 12227, "type": "language", "name": "english", "slug": "english", "url": "/language/english/", "count": 141113},
                        {"id": 129314, "type": "artist", "name": "kyaradain", "slug": "kyaradain", "url": "/artist/kyaradain/", "count": 106}
                      ],
                      "num_pages": 2,
                      "num_favorites": 100,
                      "pages": [
                        {"number": 2, "path": "galleries/3821534/2.jpg", "width": 1280, "height": 1807, "thumbnail": "galleries/3821534/2t.jpg.webp", "thumbnail_width": 200, "thumbnail_height": 282},
                        {"number": 1, "path": "galleries/3821534/1.webp", "width": 1280, "height": 1807, "thumbnail": "galleries/3821534/1t.webp", "thumbnail_width": 200, "thumbnail_height": 282}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))

        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.lastGet?.url)
        assertEquals("English Detail", post?.title)
        assertEquals("https://t.nhentai.net/galleries/3821534/thumb.webp", post?.preview?.url)
        assertEquals("https://i.nhentai.net/galleries/3821534/1.webp", post?.media?.get(0)?.url)
        assertEquals("https://i.nhentai.net/galleries/3821534/2.jpg", post?.media?.get(1)?.url)
        assertEquals(2, post?.mediaCount)
        assertEquals(listOf("english", "kyaradain"), post?.canonicalTags)
        assertEquals("kyaradain", post?.authorName)
    }

    @Test
    fun `search falls back to mirrored web page when api is cloudflare blocked`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 403,
                body = "<html>Attention Required! Cloudflare</html>",
                headers = mapOf("cf-mitigated" to listOf("challenge")),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    Title: test - Search - nhentai

                    Markdown Content:
                    ## 2,300 results

                    [![Image 1: First Mirrored Gallery](https://t4.nhentai.net/galleries/3962373/thumb.webp) First Mirrored Gallery](https://nhentai.net/g/653211/)
                    [1](https://nhentai.net/search?q=test&sort=date&page=1)[2](https://nhentai.net/search?q=test&sort=date&page=2)
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val page = adapter.search(multiTagQuery(), pageToken = null)

        assertEquals(2, httpClient.requests.size)
        assertEquals("https://nhentai.net/api/v2/search", httpClient.requests[0].url)
        assertEquals(
            "https://r.jina.ai/http://nhentai.net/search/?q=big%20breasts%20english&page=1&sort=date",
            httpClient.requests[1].url,
        )
        assertEquals("2", page.nextPageToken)
        val post = page.items.single()
        assertEquals("653211", post.id.sourcePostId)
        assertEquals("First Mirrored Gallery", post.title)
        assertEquals("https://t.nhentai.net/galleries/3962373/thumb.webp", post.preview.url)
        assertNull(post.full)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `resolve post falls back to mirrored reader page when api is cloudflare blocked`() = runTest {
        val httpClient = QueueHttpClient(
            SourceHttpResponse(
                statusCode = 403,
                body = "<html>Attention Required! Cloudflare</html>",
                headers = mapOf("cf-mitigated" to listOf("challenge")),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    Title: Mirrored Gallery - Page 1

                    Markdown Content:
                    [](http://nhentai.net/g/634609/)

                    1 of 3[](http://nhentai.net/g/634609/2/)[](http://nhentai.net/g/634609/3/)

                    [![Image 2: Page 1](https://i2.nhentai.net/galleries/3821534/1.webp)](http://nhentai.net/g/634609/2/)
                """.trimIndent(),
            ),
            SourceHttpResponse(
                statusCode = 200,
                body = """
                    <html>
                      <body>
                        <section id="tags">
                          <a href="/tag/big-breasts/" class="tag"><span class="name">big breasts</span></a>
                          <a href="/artist/yomoda-yomo/" class="tag"><span class="name">yomoda yomo</span></a>
                          <a href="/language/english/" class="tag"><span class="name">english</span></a>
                        </section>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )
        val adapter = NhentaiSourceAdapter(httpClient = httpClient)

        val post = adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))

        assertNotNull(post)
        assertEquals(3, httpClient.requests.size)
        assertEquals("https://nhentai.net/api/v2/galleries/634609", httpClient.requests[0].url)
        assertEquals("https://r.jina.ai/http://nhentai.net/g/634609/1/", httpClient.requests[1].url)
        assertEquals("https://nhentai.to/g/634609/", httpClient.requests[2].url)
        assertEquals("Mirrored Gallery", post?.title)
        assertEquals(3, post?.media?.size)
        assertEquals("https://i.nhentai.net/galleries/3821534/1.webp", post?.media?.get(0)?.url)
        assertEquals(
            listOf(
                "https://i.nhentai.net/galleries/3821534/1.webp",
                "https://i.nhentai.net/galleries/3821534/1.jpg",
                "https://i.nhentai.net/galleries/3821534/1.png",
                "https://i.nhentai.net/galleries/3821534/1.gif",
            ),
            post?.media?.get(0)?.progressiveUrls,
        )
        assertEquals("https://i.nhentai.net/galleries/3821534/3.webp", post?.media?.get(2)?.url)
        assertEquals(listOf("big breasts", "yomoda yomo", "english"), post?.canonicalTags)
        assertEquals(
            listOf(
                Triple(SearchFacet.TAG, "tag", "big breasts"),
                Triple(SearchFacet.ARTIST, "artist", "yomoda yomo"),
                Triple(SearchFacet.LANGUAGE, "language", "english"),
            ),
            post?.taxonomy?.map { term -> Triple(term.facet, term.sourceNamespace, term.value) },
        )
        assertEquals("yomoda yomo", post?.authorName)
    }

    @Test
    fun `exact tag lookup cancellation stops search instead of falling through`() = runTest {
        val expected = CancellationException("query replaced")
        val httpClient = CancellingTagLookupHttpClient(expected)
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        var thrown: CancellationException? = null
        try {
            adapter.search(sampleQuery(), pageToken = null)
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue(thrown === expected)
        assertEquals(0, httpClient.getCalls)
    }

    @Test
    fun `mirror metadata cancellation stops resolution without trying another mirror`() = runTest {
        val expected = CancellationException("viewer closed")
        val httpClient = CancellingMirrorMetadataHttpClient(expected)
        val adapter = NhentaiSourceAdapter(httpClient = httpClient, minRequestIntervalMs = 0L)

        var thrown: CancellationException? = null
        try {
            adapter.resolvePost(PostId(source = SourceKey.NHENTAI, sourcePostId = "634609"))
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue(thrown === expected)
        assertEquals(3, httpClient.getCalls)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = listOf("big breasts"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private fun multiTagQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = listOf("big breasts", "english"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private class QueueHttpClient(
        vararg responses: SourceHttpResponse,
    ) : com.theoriacodex.sources.http.SourceHttpClient {
        val requests = mutableListOf<com.theoriacodex.sources.testing.RecordedRequest>()
        val postRequests = mutableListOf<com.theoriacodex.sources.testing.RecordedPost>()
        private val queue = ArrayDeque(responses.toList())

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            requests += com.theoriacodex.sources.testing.RecordedRequest(url, query, headers)
            return queue.removeFirst()
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            error("POST is not used by NHentai tests")
        }

        override suspend fun postJson(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            postRequests += com.theoriacodex.sources.testing.RecordedPost(
                url = url,
                form = emptyMap(),
                headers = headers,
                body = body,
            )
            return queue.removeFirst()
        }
    }

    private class CancellingTagLookupHttpClient(
        private val cancellation: CancellationException,
    ) : com.theoriacodex.sources.http.SourceHttpClient {
        var getCalls = 0
            private set

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            getCalls += 1
            return SourceHttpResponse(
                statusCode = 200,
                body = """{"result":[],"num_pages":1,"per_page":25}""",
            )
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("Form POST is not used by NHentai tests")

        override suspend fun postJson(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): SourceHttpResponse = throw cancellation
    }

    private class CancellingMirrorMetadataHttpClient(
        private val cancellation: CancellationException,
    ) : com.theoriacodex.sources.http.SourceHttpClient {
        var getCalls = 0
            private set

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse {
            getCalls += 1
            return when (getCalls) {
                1 -> SourceHttpResponse(
                    statusCode = 403,
                    body = "<html>Attention Required! Cloudflare</html>",
                    headers = mapOf("cf-mitigated" to listOf("challenge")),
                )
                2 -> SourceHttpResponse(
                    statusCode = 200,
                    body = """
                        Title: Mirrored Gallery - Page 1

                        Markdown Content:
                        1 of 1
                        [![Image 1: Page 1](https://i2.nhentai.net/galleries/3821534/1.webp)](http://nhentai.net/g/634609/1/)
                    """.trimIndent(),
                )
                else -> throw cancellation
            }
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("POST is not used by NHentai tests")
    }
}
