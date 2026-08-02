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

internal abstract class NhentaiSourceAdapterTestFixture {
    protected fun sampleQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = listOf("big breasts"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    protected fun multiTagQuery(): Query {
        return Query(
            mode = QueryMode.Source(SourceKey.NHENTAI),
            includeTags = listOf("big breasts", "english"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )
    }

    protected class QueueHttpClient(
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

    protected class CancellingTagLookupHttpClient(
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

    protected class CancellingMirrorMetadataHttpClient(
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
