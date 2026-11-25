package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.RelatedPostsSourceAdapter
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GelbooruRelatedPostsSourceAdapterTest {
    @Test
    fun `related posts hydrate bounded ids with at most two requests and preserve order`() = runTest {
        val httpClient = RelatedGelbooruHttpClient(
            html = RELATED_HTML,
            malformedIds = setOf("102"),
        )
        val adapter = gelbooru(httpClient)

        val posts = (adapter as RelatedPostsSourceAdapter).relatedPosts(PostId(SourceKey.GELBOORU, "100"), limit = 6)

        assertEquals(listOf("101", "103"), posts.map { post -> post.id.sourcePostId })
        assertTrue(httpClient.maxConcurrentHydrations <= 2)
        assertEquals(listOf("101", "102", "103"), httpClient.hydratedIds.sorted())
    }

    @Test
    fun `related posts survive single failures and rethrow the first failure only when none resolve (ADR 0042)`() = runTest {
        suspend fun related(failing: Set<String>, malformed: Set<String> = emptySet()) = runCatching {
            val client = RelatedGelbooruHttpClient(RELATED_HTML, malformedIds = malformed, failingIds = failing)
            (gelbooru(client) as RelatedPostsSourceAdapter).relatedPosts(PostId(SourceKey.GELBOORU, "100"), limit = 6)
        }

        assertEquals(listOf("101", "103"), related(setOf("102")).getOrThrow().map { post -> post.id.sourcePostId })
        assertEquals("101 unavailable", related(setOf("101", "102", "103")).exceptionOrNull()?.cause?.message)
        assertEquals("101 unavailable", related(setOf("101", "103"), malformed = setOf("102")).exceptionOrNull()?.cause?.message)
        assertEquals(emptyList<String>(), related(emptySet(), malformed = setOf("101", "102", "103")).getOrThrow())
    }

    private fun gelbooru(httpClient: SourceHttpClient) = GelbooruSourceAdapter(GelbooruSite.GELBOORU, httpClient)
}

private const val RELATED_HTML = """
    <div>
      <div>More Like This: (Beta Temporary Feature)</div>
      <a href="index.php?page=post&amp;s=view&amp;id=101"><img src="1.jpg"></a>
      <a href="index.php?page=post&amp;s=view&amp;id=102"><img src="2.jpg"></a>
      <a href="index.php?page=post&amp;s=view&amp;id=103"><img src="3.jpg"></a>
    </div>
"""

private class RelatedGelbooruHttpClient(
    private val html: String,
    private val malformedIds: Set<String> = emptySet(),
    private val failingIds: Set<String> = emptySet(),
) : SourceHttpClient {
    private val lock = Any()
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
        synchronized(lock) {
            hydratedIds += id
            activeHydrations += 1
            maxConcurrentHydrations = maxOf(maxConcurrentHydrations, activeHydrations)
        }
        delay(1)
        synchronized(lock) { activeHydrations -= 1 }
        if (id in failingIds) throw IOException("$id unavailable")
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
