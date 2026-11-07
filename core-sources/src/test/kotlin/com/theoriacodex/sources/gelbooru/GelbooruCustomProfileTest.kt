package com.theoriacodex.sources.gelbooru

import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceHttpResponse
import com.theoriacodex.sources.testing.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A third Gelbooru-family site described only by data, using a recorded Safebooru response shape (URLs replaced). */
class GelbooruCustomProfileTest {
    @Test
    fun `a custom profile reads safebooru shaped posts and autocomplete`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(statusCode = 200, body = SAFEBOORU_POSTS)
        }
        val adapter = GelbooruSourceAdapter(SAFEBOORU_SHAPED_SITE, httpClient)
        val query = Query(
            mode = QueryMode.Source(SourceKey.GELBOORU),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            sort = SortMode.POPULAR,
            dateRange = null,
            minScore = null,
        ).withIncludeTermGroups(listOf(SearchTermGroup(listOf(SearchTerm("cat"), SearchTerm("dog")))))

        val post = adapter.search(query, pageToken = null).items.single()

        assertEquals("https://safebooru.example/index.php", httpClient.lastGet?.url)
        assertEquals("( cat ~ dog ) sort:score:desc", httpClient.lastGet?.query?.get("tags"))
        assertNull(httpClient.lastGet?.query?.get("user_id"))
        assertEquals("https://safebooru.example/index.php?page=post&s=view&id=7293991", post.pageUrl)
        assertEquals("image/jpeg", post.full?.mime)
        assertEquals(1_793_795_424_000L, post.createdAtEpochMs)
        assertEquals("user:danbooru", post.creatorProfile?.uploadsQuery)
        assertNull(post.creatorProfile?.profileUrl)

        httpClient.nextGetResponse = SourceHttpResponse(statusCode = 200, body = "")
        val uploads = (adapter as CreatorPostsSourceAdapter).searchCreatorPosts(requireNotNull(post.creatorProfile), null)
        assertEquals(emptyList<Any>(), uploads.items)

        httpClient.nextGetResponse = SourceHttpResponse(200, """[{"label":"landscape (9927)","value":"landscape"}]""")
        val suggestion = adapter.autocompleteTags("land", limit = 5).single()
        assertEquals("https://safebooru.example/autocomplete.php", httpClient.lastGet?.url)
        assertEquals(9927, suggestion.count)
    }
}

/**
 * Safebooru answers posts as a bare JSON array with `directory` + `image` beside `file_url`, no
 * `file_ext`, `created_at` or `creator_id`, and only `change` as an epoch timestamp. Its OR groups
 * use parentheses, and tag suggestions come from `autocomplete.php`.
 */
private val SAFEBOORU_SHAPED_SITE = GelbooruSite(
    source = SourceKey.GELBOORU,
    label = "Safebooru",
    siteUrl = "https://safebooru.example",
    autocompleteUrl = "https://safebooru.example/autocomplete.php",
    orGroup = GelbooruOrGroup(prefix = "( ", separator = " ~ ", suffix = " )"),
    creatorPosts = true,
)

private val SAFEBOORU_POSTS = """
    [{"preview_url":"https://safebooru.example/thumbnails/1/thumbnail_a.jpg",
      "sample_url":"https://safebooru.example/images/1/a.jpg",
      "file_url":"https://safebooru.example/images/1/a.jpg",
      "directory":1,"hash":"00000000000000000000000000000000","width":1012,"height":1500,
      "id":7293991,"image":"a.jpg","change":1793795424,"owner":"danbooru","parent_id":0,
      "rating":"general","sample":false,"sample_height":0,"sample_width":0,"score":null,
      "tags":"landscape sky","source":"","status":"active","has_notes":false,"comment_count":0}]
""".trimIndent()
