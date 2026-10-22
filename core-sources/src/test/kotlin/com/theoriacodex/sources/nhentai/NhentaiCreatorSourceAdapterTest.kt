package com.theoriacodex.sources.nhentai

import com.google.gson.JsonParser
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NhentaiCreatorSourceAdapterTest {
    @Test
    fun `tag count lookup publishes completed counts before a later request times out`() = runTest {
        var requests = 0
        val http = object : SourceHttpClient by FakeHttpClient() {
            override suspend fun postJson(url: String, body: String, headers: Map<String, String>): SourceHttpResponse {
                requests++
                if (requests > 1) delay(10_000)
                return SourceHttpResponse(200, """[{"id":1,"type":"tag","name":"first","count":42}]""")
            }
        }
        val adapter = NhentaiSourceAdapter(http, minRequestIntervalMs = 0)
        val counts = mutableMapOf<String, Int>()

        val completed = withTimeoutOrNull(4_000) {
            adapter.fetchTagCountsIncrementally(listOf("first", "second")) { counts.putAll(it) }
            true
        }

        assertNull(completed)
        assertEquals(mapOf("first" to 42), counts)
        assertEquals(2, requests)
    }

    @Test
    fun `artist tags become distinct profiles without treating other tags as creators`() {
        val post = requireNotNull(parseNhentaiGallery(JsonParser.parseString("""
            {"id":552559,"media_id":"123","tags":[
                {"type":"artist","name":"darkmaya"},
                {"type":"artist","name":"Darkmaya"},
                {"type":"artist","name":"Artist Two"},
                {"type":"group","name":"Studio"},
                {"type":"tag","name":"Other"}
            ]}
        """).asJsonObject))

        assertEquals(listOf("darkmaya", "Artist Two"), post.creatorProfiles.map { it.displayName })
        assertEquals(post.creatorProfiles.first(), post.creatorProfile)
        assertEquals("darkmaya", post.authorName)
        assertEquals("https://nhentai.net/artist/darkmaya/", post.creatorProfile?.profileUrl)
        assertEquals("artist:darkmaya", post.creatorProfile?.uploadsQuery)
        assertEquals("https://nhentai.net/artist/artist-two/", post.creatorProfiles[1].profileUrl)
        assertEquals("artist:\"artist two\"", post.creatorProfiles[1].uploadsQuery)
    }

    @Test
    fun `creator feed scopes artist names and forwards pagination`() = runTest {
        val http = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(200, """{"result":[],"num_pages":3}""")
        }
        val adapter = NhentaiSourceAdapter(http, minRequestIntervalMs = 0)
        val creator = CreatorProfile(SourceKey.NHENTAI, "Artist Two", "artist two", uploadsQuery = "artist:\"artist two\"")

        val page = adapter.searchCreatorPosts(creator, "2")

        assertEquals("https://nhentai.net/api/v2/search", http.lastGet?.url)
        assertEquals(mapOf("page" to "2", "query" to "artist:\"artist two\""), http.lastGet?.query)
        assertEquals("3", page.nextPageToken)
    }

    @Test
    fun `foreign and missing artist identities do not make requests`() = runTest {
        val http = FakeHttpClient()
        val adapter = NhentaiSourceAdapter(http, minRequestIntervalMs = 0)
        listOf(
            CreatorProfile(SourceKey.PIXIV, "Artist", "123"),
            CreatorProfile(SourceKey.NHENTAI, "Unknown"),
        ).forEach { creator ->
            val page = adapter.searchCreatorPosts(creator, null)
            assertTrue(page.items.isEmpty())
            assertNull(page.nextPageToken)
        }
        assertNull(http.lastGet)
    }
}
