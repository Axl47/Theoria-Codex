package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.credentials.GelbooruCredentials
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeCredentialsProvider
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GelbooruSourceAdapterTest {
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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = credentials,
        )

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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

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
                body = """{"post":[{"id":"12345678","preview_url":"https://gelbooru.com/p.jpg","file_url":"https://gelbooru.com/f.jpg","tags":"a b"}]}""",
            )
        }
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

        val page = adapter.search(sampleQuery(), pageToken = null)

        assertEquals(
            "https://gelbooru.com/index.php?page=post&s=view&id=12345678",
            page.items.firstOrNull()?.pageUrl,
        )
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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

        val page = adapter.searchCreatorPosts(
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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

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
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

        val post = adapter.search(sampleQuery(), pageToken = null).items.first()

        assertEquals("https://gelbooru.com/preview.jpg", post.preview.url)
        assertEquals("https://gelbooru.com/full.jpg", post.full?.url)
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
                        {"name":"landscape","count":"560"}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val adapter = GelbooruSourceAdapter(
            httpClient = httpClient,
            credentialsProvider = FakeCredentialsProvider(),
        )

        val counts = adapter.fetchTagCounts(listOf("blue hair", "landscape"))

        assertEquals("blue_hair landscape", httpClient.lastGet?.query?.get("names"))
        assertEquals("2", httpClient.lastGet?.query?.get("limit"))
        assertEquals(1200, counts["blue_hair"])
        assertEquals(560, counts["landscape"])
        assertNull(counts["missing"])
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
}
