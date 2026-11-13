package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.credentials.Rule34XxxCredentials
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One suite for the Gelbooru family: shared behavior runs on [GelbooruSite.GELBOORU], then per-preset cases. */
class GelbooruSourceAdapterTest {
    @Test
    fun `related posts hydrate bounded ids with at most two requests and preserve order`() = runTest {
        val httpClient = RelatedGelbooruHttpClient(
            html = """
                <div>
                  <div>More Like This: (Beta Temporary Feature)</div>
                  <a href="index.php?page=post&amp;s=view&amp;id=101"><img src="1.jpg"></a>
                  <a href="index.php?page=post&amp;s=view&amp;id=102"><img src="2.jpg"></a>
                  <a href="index.php?page=post&amp;s=view&amp;id=103"><img src="3.jpg"></a>
                </div>
            """.trimIndent(),
            malformedIds = setOf("102"),
        )
        val adapter = gelbooru(httpClient)

        val posts = (adapter as RelatedPostsSourceAdapter).relatedPosts(PostId(SourceKey.GELBOORU, "100"), limit = 6)

        assertEquals(listOf("101", "103"), posts.map { post -> post.id.sourcePostId })
        assertTrue(httpClient.maxConcurrentHydrations <= 2)
        assertEquals(listOf("101", "102", "103"), httpClient.hydratedIds.sorted())
    }

    @Test
    fun `search compiles required groups with native Gelbooru OR braces`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = "{\"post\":[]}")
        }
        val adapter = gelbooru(httpClient)
        val query = sampleQuery().withIncludeTermGroups(
            listOf(
                SearchTermGroup.single(SearchTerm("landscape")),
                SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog"))),
            )
        )

        adapter.search(query, pageToken = null)

        assertEquals(
            "landscape {cat ~ dog} sort:id:desc",
            httpClient.lastGet?.query?.get("tags"),
        )
        assertTrue(adapter.capabilities.supportsGroupedIncludeTagsServerSide)
    }

    @Test
    fun `search attaches credentials when configured`() = runTest {
        val credentials = FakeCredentialsProvider().apply {
            gelbooruCredentials = GelbooruCredentials(userId = "user1", apiKey = "key1")
        }
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"post":[{"id":"1","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b"}]}""",
            )
        }
        val adapter = gelbooru(httpClient, credentials)

        adapter.search(sampleQuery(), pageToken = null)

        assertEquals("user1", httpClient.lastGet?.query?.get("user_id"))
        assertEquals("key1", httpClient.lastGet?.query?.get("api_key"))
    }

    @Test
    fun `search maps auth blocked response to auth required`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"success":false,"message":"API key required"}""",
            )
        }
        val adapter = gelbooru(httpClient)

        val failure = runCatching { adapter.search(sampleQuery(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, failure.reason)
        assertTrue(failure.message?.contains("credentials", ignoreCase = true) == true)
    }

    @Test
    fun `search maps canonical index php post url`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """{"post":[{"id":"12345678","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","md5":"0123456789abcdef0123456789abcdef","source":"https://x.com/artist/status/1","tags":"a jojo&#039;s_bizarre_adventure"}]}""",
            )
        }
        val adapter = gelbooru(httpClient)

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals(
            "https://gelbooru.com/index.php?page=post&s=view&id=12345678",
            page.items.firstOrNull()?.pageUrl,
        )
        assertEquals(listOf("a", "jojo's_bizarre_adventure"), page.items.firstOrNull()?.canonicalTags)
        assertEquals("0123456789abcdef0123456789abcdef", page.items.firstOrNull()?.contentHash)
        assertEquals("https://x.com/artist/status/1", page.items.firstOrNull()?.originUrl)
    }

    @Test
    fun `search keeps pagination when a full provider page contains an unparseable post`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = fullProviderPageWithMalformedPost(),
            )
        }
        val adapter = gelbooru(httpClient)

        val page = adapter.search(sampleQuery(), pageToken = "1")

        assertEquals(39, page.items.size)
        assertEquals("2", page.nextPageToken)
    }

    @Test
    fun `creator search keeps pagination when a full provider page contains an unparseable post`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = fullProviderPageWithMalformedPost(),
            )
        }
        val adapter = gelbooru(httpClient)

        val page = (adapter as CreatorPostsSourceAdapter).searchCreatorPosts(
            creator = CreatorProfile(
                source = SourceKey.GELBOORU,
                displayName = "artist",
                uploadsQuery = "user:artist",
            ),
            pageToken = "2",
        )

        assertEquals(39, page.items.size)
        assertEquals("3", page.nextPageToken)
    }

    @Test
    fun `search accepts direct arrays and ignores malformed optional values`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      null,
                      "not-a-post",
                      {
                        "id":123,
                        "preview_url":{},
                        "file_url":"https://gelbooru.com/full.jpg",
                        "tags":[],
                        "width":"wide",
                        "created_at":"not-a-timestamp"
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.single()

        assertEquals("123", post.id.sourcePostId)
        assertEquals("https://gelbooru.com/full.jpg", post.preview.url)
        assertNull(post.width)
        assertNull(post.createdAtEpochMs)
        assertEquals(emptyList<String>(), post.canonicalTags)
    }

    @Test
    fun `search maps creator metadata`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"123","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b","owner":"ssfl","creator_id":"179338"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertNotNull(post.creatorProfile)
        assertEquals("ssfl", post.creatorProfile?.displayName)
        assertEquals("179338", post.creatorProfile?.profileId)
        assertEquals("user:ssfl", post.creatorProfile?.uploadsQuery)
        assertEquals(
            "https://gelbooru.com/index.php?page=account&s=profile&id=179338",
            post.creatorProfile?.profileUrl,
        )
    }

    @Test
    fun `creator search uses uploader token and leaves regular search unchanged`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"444","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b","owner":"ssfl","creator_id":"179338"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val page = (adapter as CreatorPostsSourceAdapter).searchCreatorPosts(
            creator = CreatorProfile(
                source = SourceKey.GELBOORU,
                displayName = "ssfl",
                profileId = "179338",
                profileUrl = "https://gelbooru.com/index.php?page=account&s=profile&id=179338",
                uploadsQuery = "user:ssfl",
            ),
            pageToken = "2",
        )

        assertEquals("user:ssfl", httpClient.lastGet?.query?.get("tags"))
        assertEquals("2", httpClient.lastGet?.query?.get("pid"))
        assertEquals("444", page.items.first().id.sourcePostId)

        adapter.search(sampleQuery(), pageToken = null)
        assertEquals("landscape sort:id:desc", httpClient.lastGet?.query?.get("tags"))

        adapter.search(sampleQuery().withIncludeTermGroups(emptyList()), pageToken = null)
        assertEquals("", httpClient.lastGet?.query?.get("tags"))
    }

    @Test
    fun `search without owner yields non browseable creator profile`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"123","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b","creator_id":"179338"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals("179338", post.creatorProfile?.displayName)
        assertNull(post.creatorProfile?.uploadsQuery)
    }

    @Test
    fun `search maps video mime for gelbooru posts`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"777","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.mp4","file_ext":"mp4","duration":"10","tags":"a b"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals("image/jpeg", post.preview.mime)
        assertEquals("video/mp4", post.full?.mime)
        assertEquals(10_000L, post.durationMs)
    }

    @Test
    fun `search parses colon formatted gelbooru video duration`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"778","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.mp4","file_ext":"mp4","duration":"0:10","tags":"a b"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals(10_000L, post.durationMs)
    }

    @Test
    fun `search treats large numeric gelbooru video duration as milliseconds`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"779","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.mp4","file_ext":"mp4","duration":10000,"tags":"a b"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals(10_000L, post.durationMs)
    }

    @Test
    fun `search maps gelbooru sample url as progressive viewer candidate`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"888","preview_url":"https://gelbooru.com/preview.jpg","sample_url":"https://gelbooru.com/sample.jpg","file_url":"https://gelbooru.com/full.jpg","tags":"a b"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals("https://gelbooru.com/preview.jpg", post.preview.url)
        assertEquals("https://gelbooru.com/full.jpg", post.full?.url)
        assertEquals(listOf("https://gelbooru.com/sample.jpg"), post.full?.progressiveUrls)
    }

    @Test
    fun `search canonicalizes numbered video cdn urls with invalid host certificates`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {"post":[{"id":"889","preview_url":"https://video-cdn4.gelbooru.com/preview.jpg","sample_url":"https://video-cdn4.gelbooru.com/sample.jpg","file_url":"https://video-cdn4.gelbooru.com/videos/file.mp4","file_ext":"mp4","tags":"a b"}]}
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals("https://gelbooru.com/preview.jpg", post.preview.url)
        assertEquals("https://gelbooru.com/videos/file.mp4", post.full?.url)
        assertEquals(listOf("https://gelbooru.com/sample.jpg"), post.full?.progressiveUrls)
    }

    @Test
    fun `fetch tag counts batches names lookup and returns counts`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "tag": [
                        {"name":"blue_hair","count":"1200"},
                        {"name":"landscape","count":"560"},
                        {"name":"jojo&#039;s_bizarre_adventure","count":"9"}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = gelbooru(httpClient)

        val counts = adapter.fetchTagCounts(listOf("blue hair", "landscape", "jojo's bizarre adventure"))

        assertEquals("blue_hair landscape jojo's_bizarre_adventure", httpClient.lastGet?.query?.get("names"))
        assertEquals("3", httpClient.lastGet?.query?.get("limit"))
        assertEquals(9, counts["jojo's_bizarre_adventure"])
        assertEquals(1200, counts["blue_hair"])
        assertEquals(560, counts["landscape"])
        assertNull(counts["missing"])
    }

    // Rule34.xxx preset: separate API host, required key, bare-array JSON, autocomplete.php.

    @Test
    fun `rule34 search attaches credentials and maps canonical post url`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {
                        "id":"123456",
                        "preview_url":"https://img.rule34.xxx/preview.jpg",
                        "file_url":"https://img.rule34.xxx/full.jpg",
                        "hash":"fedcba9876543210fedcba9876543210",
                        "tags":"genshin_impact lumine"
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = rule34(httpClient, rule34Credentials())

        val page = adapter.search(rule34Query(), pageToken = null)

        assertEquals("https://api.rule34.xxx/index.php", httpClient.lastGet?.url)
        assertEquals("Mozilla/5.0", httpClient.lastGet?.headers?.get("User-Agent"))
        assertEquals("42", httpClient.lastGet?.query?.get("user_id"))
        assertEquals("secret", httpClient.lastGet?.query?.get("api_key"))
        assertEquals(
            "https://rule34.xxx/index.php?page=post&s=view&id=123456",
            page.items.first().pageUrl,
        )
        assertEquals("fedcba9876543210fedcba9876543210", page.items.first().contentHash)
    }

    @Test
    fun `rule34 spells sorts and post lookups its own way`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = "[]")
        }
        val adapter = rule34(httpClient, rule34Credentials())

        adapter.search(rule34Query().copy(sort = SortMode.TOP), pageToken = null)
        assertEquals("genshin_impact sort:score", httpClient.lastGet?.query?.get("tags"))

        adapter.search(rule34Query().withIncludeTermGroups(listOf(SearchTermGroup.single(SearchTerm("Blue Hair")))), null)
        assertEquals("blue_hair", httpClient.lastGet?.query?.get("tags"))

        adapter.resolvePost(PostId(SourceKey.RULE34XXX, "77"))
        assertEquals("id:77", httpClient.lastGet?.query?.get("tags"))
        assertNull(httpClient.lastGet?.query?.get("id"))
    }

    @Test
    fun `rule34 exposes neither creator nor related posts`() {
        val rule34 = rule34(FakeHttpClient())
        val gelbooru = gelbooru(FakeHttpClient())

        assertFalse(rule34 is CreatorPostsSourceAdapter)
        assertFalse(rule34 is RelatedPostsSourceAdapter)
        assertTrue(rule34.capabilities.requiresCredentials)
        assertFalse(rule34.capabilities.supportsGroupedIncludeTagsServerSide)
        assertTrue(gelbooru is CreatorPostsSourceAdapter)
        assertTrue(gelbooru is RelatedPostsSourceAdapter)
    }

    @Test
    fun `rule34 autocomplete parses json suggestions`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      {"label":"genshin_impact (237880)","value":"genshin_impact"},
                      {"label":"genshin (59)","value":"genshin"}
                    ]
                """.trimIndent(),
            )
        }
        val adapter = rule34(httpClient)

        val suggestions = adapter.autocompleteTags("gensh", limit = 10)

        assertEquals("https://api.rule34.xxx/autocomplete.php", httpClient.lastGet?.url)
        assertEquals("genshin_impact", suggestions.first().text)
        assertEquals(237880, suggestions.first().count)
        assertEquals("genshin", suggestions[1].text)
    }

    @Test
    fun `rule34 search keeps pagination when a full provider page contains an unparseable post`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = buildString {
                    append("[")
                    repeat(40) { index ->
                        if (index > 0) append(",")
                        if (index == 39) {
                            append("""{"id":null,"tags":"malformed"}""")
                        } else {
                            append("""{"id":"${index + 1}","tags":"tag${index}"}""")
                        }
                    }
                    append("]")
                },
            )
        }
        val adapter = rule34(httpClient, rule34Credentials())

        val page = adapter.search(rule34Query(), pageToken = "1")

        assertEquals(39, page.items.size)
        assertEquals("2", page.nextPageToken)
    }

    @Test
    fun `rule34 search skips non-object records and tolerates malformed optional values`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      null,
                      "not-a-post",
                      {
                        "id":123456,
                        "preview_url":{},
                        "file_url":"https://img.rule34.xxx/full.jpg",
                        "tags":[],
                        "width":"wide",
                        "created_at":"not-a-timestamp"
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = rule34(httpClient, rule34Credentials())

        val post = adapter.search(rule34Query(), pageToken = null).items.single()

        assertEquals("123456", post.id.sourcePostId)
        assertEquals("https://img.rule34.xxx/full.jpg", post.preview.url)
        assertNull(post.width)
        assertNull(post.createdAtEpochMs)
        assertEquals(emptyList<String>(), post.canonicalTags)
        assertNull(post.creatorProfile)
    }

    @Test
    fun `rule34 search without credentials returns auth required`() = runTest {
        val adapter = rule34(FakeHttpClient())

        val failure = runCatching { adapter.search(rule34Query(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, failure.reason)
        assertTrue(failure.message?.contains("credentials", ignoreCase = true) == true)
    }

    @Test
    fun `rule34 maps a rejected key in a successful body to auth required`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = "\"Missing authentication. Go to api.rule34.xxx for more information\"",
            )
        }
        val adapter = rule34(httpClient, rule34Credentials())

        val failure = runCatching { adapter.search(rule34Query(), pageToken = null) }.exceptionOrNull()

        require(failure is SourceAdapterException)
        assertEquals(SourceFailureReason.AUTH_REQUIRED, failure.reason)
    }

    private fun gelbooru(
        httpClient: SourceHttpClient,
        credentials: SourceCredentialsProvider = FakeCredentialsProvider(),
    ): GelbooruFamilySourceAdapter = GelbooruSourceAdapter(GelbooruSite.GELBOORU, httpClient, credentials::getGelbooruCredentials)

    private fun rule34(
        httpClient: SourceHttpClient,
        credentials: SourceCredentialsProvider = FakeCredentialsProvider(),
    ): GelbooruFamilySourceAdapter = GelbooruSourceAdapter(GelbooruSite.RULE34XXX, httpClient, credentials::getRule34XxxCredentials)

    private fun rule34Credentials() = FakeCredentialsProvider().apply {
        rule34XxxCredentials = Rule34XxxCredentials(userId = "42", apiKey = "secret")
    }

    private fun rule34Query(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.RULE34XXX),
            includeTags = listOf("genshin_impact"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.GELBOORU),
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    private fun fullProviderPageWithMalformedPost(): String = buildString {
        append("{\"post\":[")
        repeat(40) { index ->
            if (index > 0) append(",")
            if (index == 39) {
                append("""{"id":null,"tags":"malformed"}""")
            } else {
                append("""{"id":"${index + 1}","tags":"tag${index}"}""")
            }
        }
        append("]}")
    }
}

private class RelatedGelbooruHttpClient(
    private val html: String,
    private val malformedIds: Set<String> = emptySet(),
) : SourceHttpClient {
    val hydratedIds = mutableListOf<String>()
    var maxConcurrentHydrations: Int = 0
        private set
    private var activeHydrations: Int = 0

    override suspend fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        if (query["page"] == "post") return SourceHttpResponse(200, html)
        val id = requireNotNull(query["id"])
        hydratedIds += id
        activeHydrations += 1
        maxConcurrentHydrations = maxOf(maxConcurrentHydrations, activeHydrations)
        delay(1)
        activeHydrations -= 1
        val body = if (id in malformedIds) {
            """{"post":[{}]}"""
        } else {
            """{"post":[{"id":"$id","preview_url":"https://gelbooru.com/$id-preview.jpg","file_url":"https://gelbooru.com/$id.jpg","tags":"landscape"}]}"""
        }
        return SourceHttpResponse(200, body)
    }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>,
    ): SourceHttpResponse = error("unused")
}
