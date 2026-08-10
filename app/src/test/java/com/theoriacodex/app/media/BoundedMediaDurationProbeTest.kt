package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedMediaDurationProbeTest {
    @Test
    fun `complete bounded response parses once with source scoped headers`() = runTest {
        val media = mp4File(durationMs = 5_000)
        val http = FakeHttpClient(media, ignoreRange = true)
        val probe = probe(http)

        val result = probe.probe(post("head"))

        assertEquals(
            MediaDurationState.Known(5_000L, MediaDurationProvenance.CONTAINER_PROBE),
            result,
        )
        assertEquals(1, http.requests.size)
        assertEquals(SourceByteRange(0L, TEST_WINDOW_BYTES - 1L), http.requests.single().range)
        assertEquals(TEST_WINDOW_BYTES, http.requests.single().maxBodyBytes)
        assertTrue("Referer" in http.requests.single().headers)
        assertTrue("User-Agent" in http.requests.single().headers)
    }

    @Test
    fun `tail metadata uses two exact bounded ranges`() = runTest {
        val media = mp4WithTailDuration(durationMs = 8_000)
        val http = FakeHttpClient(media)
        val result = probe(http).probe(post("tail"))

        assertEquals(
            MediaDurationState.Known(8_000L, MediaDurationProvenance.CONTAINER_PROBE),
            result,
        )
        assertEquals(2, http.requests.size)
        assertEquals(SourceByteRange(0L, TEST_WINDOW_BYTES - 1L), http.requests[0].range)
        assertEquals(
            SourceByteRange(media.size - TEST_WINDOW_BYTES.toLong(), media.size - 1L),
            http.requests[1].range,
        )
        assertTrue(http.requests.all { request -> request.maxBodyBytes == TEST_WINDOW_BYTES })
    }

    @Test
    fun `partial response without authoritative content range is retryable`() = runTest {
        val http = FakeHttpClient(mp4File(5_000), omitContentRange = true)

        assertEquals(
            MediaDurationState.RetryableFailure(110L, MediaDurationFailureReason.TRANSPORT_FAILURE),
            probe(http).probe(post("range")),
        )
    }

    @Test
    fun `ignored range cannot smuggle an oversized full body`() = runTest {
        val http = FakeHttpClient(ByteArray(TEST_WINDOW_BYTES + 1), ignoreRange = true)

        assertEquals(
            MediaDurationState.RetryableFailure(110L, MediaDurationFailureReason.TRANSPORT_FAILURE),
            probe(http).probe(post("oversized")),
        )
    }

    @Test
    fun `unsupported bounded container settles without another request`() = runTest {
        val http = FakeHttpClient("not-media".toByteArray(), ignoreRange = true)

        assertEquals(
            MediaDurationState.Unsupported(MediaDurationUnsupportedReason.UNSUPPORTED_CONTAINER),
            probe(http).probe(post("unsupported")),
        )
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `overall timeout cancels transport and publishes bounded retry`() = runTest {
        val http = StalledHttpClient()
        val probe = BoundedMediaDurationProbe(
            httpClient = http,
            clock = { 100L },
            byteWindowLimit = TEST_WINDOW_BYTES,
            operationTimeoutMs = 10L,
            retryDelayMs = 10L,
        )

        assertEquals(
            MediaDurationState.RetryableFailure(110L, MediaDurationFailureReason.TIMEOUT),
            probe.probe(post("timeout")),
        )
        assertTrue(http.cancelled)
    }

    private fun probe(http: SourceHttpClient): BoundedMediaDurationProbe {
        return BoundedMediaDurationProbe(
            httpClient = http,
            clock = { 100L },
            byteWindowLimit = TEST_WINDOW_BYTES,
            operationTimeoutMs = 1_000L,
            retryDelayMs = 10L,
        )
    }

    private fun post(id: String): Post {
        val full = ImageRef(
            url = "https://example.test/$id.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        return Post(
            id = PostId(SourceKey.HITOMI, id),
            preview = full,
            full = full,
            media = listOf(full),
            pageUrl = null,
            width = 100,
            height = 100,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
        )
    }

    private class FakeHttpClient(
        private val media: ByteArray,
        private val ignoreRange: Boolean = false,
        private val omitContentRange: Boolean = false,
    ) : SourceHttpClient {
        val requests = mutableListOf<Request>()

        override suspend fun getBytes(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            range: SourceByteRange?,
            maxBodyBytes: Int,
        ): SourceByteResponse {
            requests += Request(requireNotNull(range), maxBodyBytes, headers)
            if (ignoreRange) {
                return SourceByteResponse(
                    statusCode = 200,
                    body = media,
                    headers = mapOf("Content-Length" to listOf(media.size.toString())),
                )
            }
            val start = range.startInclusive.toInt().coerceAtMost(media.size)
            val endExclusive = (range.endInclusive + 1L).toInt().coerceAtMost(media.size)
            val body = media.copyOfRange(start, endExclusive)
            val responseHeaders = if (omitContentRange) {
                emptyMap()
            } else {
                mapOf(
                    "Content-Range" to listOf(
                        "bytes $start-${endExclusive - 1}/${media.size}",
                    ),
                )
            }
            return SourceByteResponse(206, body, responseHeaders)
        }

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("Text GET is not used")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("POST is not used")
    }

    private class StalledHttpClient : SourceHttpClient {
        var cancelled = false

        override suspend fun getBytes(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
            range: SourceByteRange?,
            maxBodyBytes: Int,
        ): SourceByteResponse {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        override suspend fun get(
            url: String,
            query: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("Text GET is not used")

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            headers: Map<String, String>,
        ): SourceHttpResponse = error("POST is not used")
    }

    private data class Request(
        val range: SourceByteRange,
        val maxBodyBytes: Int,
        val headers: Map<String, String>,
    )

    private companion object {
        const val TEST_WINDOW_BYTES = 64

        fun mp4File(durationMs: Int): ByteArray {
            return box("ftyp", byteArrayOf()) + box("moov", box("mvhd", movieHeader(durationMs)))
        }

        fun mp4WithTailDuration(durationMs: Int): ByteArray {
            return box("ftyp", byteArrayOf()) +
                box("mdat", ByteArray(160)) +
                box("moov", box("mvhd", movieHeader(durationMs)))
        }

        fun movieHeader(durationMs: Int): ByteArray {
            return ByteArray(20).apply {
                ByteBuffer.wrap(this, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(1_000)
                ByteBuffer.wrap(this, 16, 4).order(ByteOrder.BIG_ENDIAN).putInt(durationMs)
            }
        }

        fun box(type: String, payload: ByteArray): ByteArray {
            return ByteArrayOutputStream().apply {
                write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size + 8).array())
                write(type.toByteArray(Charsets.US_ASCII))
                write(payload)
            }.toByteArray()
        }
    }
}
