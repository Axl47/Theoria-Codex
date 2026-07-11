package com.theoriacodex.sources.aibooru

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
import org.junit.Test

class AibooruSourceAdapterTest {
    @Test
    fun `search parses posts and increments pagination token`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = buildString {
                    append("[")
                    repeat(40) { index ->
                        if (index > 0) append(",")
                        append(
                            """{"id":${index + 1},"preview_file_url":"https://aibooru.online/p${index}.jpg","file_url":"https://aibooru.online/f${index}.jpg","tag_string":"tag${index}"}"""
                        )
                    }
                    append("]")
                },
            )
        }
        val adapter = AibooruSourceAdapter(httpClient = httpClient)

        val page = adapter.search(sampleQuery(), pageToken = "1")

        assertEquals(40, page.items.size)
        assertEquals("2", page.nextPageToken)
        assertNotNull(httpClient.lastGet?.query?.get("tags"))
    }

    @Test
    fun `search safely skips non-object records and ignores malformed optional fields`() = runTest {
        val httpClient = FakeHttpClient().apply {
            nextGetResponse = SourceHttpResponse(
                statusCode = 200,
                body = """
                    [
                      null,
                      "not-a-post",
                      {
                        "id":"42",
                        "preview_file_url":{},
                        "file_url":"https://aibooru.online/full.jpg",
                        "tag_string":[],
                        "image_width":"wide",
                        "created_at":"not-a-timestamp"
                      }
                    ]
                """.trimIndent(),
            )
        }
        val adapter = AibooruSourceAdapter(httpClient = httpClient)

        val post = adapter.search(sampleQuery(), pageToken = null).items.single()

        assertEquals("42", post.id.sourcePostId)
        assertNull(post.preview.url)
        assertNull(post.width)
        assertNull(post.createdAtEpochMs)
        assertEquals(emptyList<String>(), post.canonicalTags)
    }

    private fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.AIBOORU),
            includeTags = listOf("landscape"),
            excludeTags = listOf("comic"),
            sort = SortMode.TOP,
            dateRange = null,
            minScore = 100,
        )
    }
}
