package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.http.SourceHttpResponse
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HitomiNozomiReaderTest {
    private val answers = mutableMapOf<String, SourceByteResponse>()
    private val reader = HitomiNozomiReader(
        httpClient = object : SourceHttpClient {
            override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                error("unused")

            override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): SourceHttpResponse =
                error("unused")

            override suspend fun getBytes(
                url: String,
                query: Map<String, String>,
                headers: Map<String, String>,
                range: SourceByteRange?,
                maxBodyBytes: Int,
            ): SourceByteResponse = answers.getValue(url)
        },
        knownSizeCacheMaxBytes = 1_024,
        membershipCacheMaxBytes = 1_024,
    )

    @Test
    fun `a whole index takes a 206 only from byte zero, complete and within its limit, and a missing one is empty (ADR 0043)`() = runTest {
        answers["404"] = SourceByteResponse(404, ByteArray(0))
        answers["416"] = SourceByteResponse(416, ByteArray(0))
        answers["whole"] = partial(0, 12, 7, 8, 9)
        answers["offset"] = partial(4, 12, 8, 9)
        answers["short"] = partial(0, 12, 7, 8)

        assertArrayEquals(IntArray(0), reader.membershipFor(index("404")))
        assertArrayEquals(IntArray(0), reader.membershipFor(index("416")))
        assertArrayEquals(intArrayOf(7, 8, 9), reader.membershipFor(index("whole")))
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.membershipFor(index("offset")) })
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.membershipFor(index("short")) })
        assertArrayEquals(intArrayOf(7, 8, 9), reader.readCompleteNozomi(index("whole"), maxGalleryIds = 3).ids)
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.readCompleteNozomi(index("whole"), maxGalleryIds = 2) })
    }

    @Test
    fun `a page of the primary index takes a 206 only from the requested offset (ADR 0043)`() = runTest {
        answers["requested"] = partial(8, 16, 3, 4)
        answers["start"] = partial(0, 16, 1, 2)

        assertArrayEquals(intArrayOf(3, 4), reader.readPrimaryRange(index("requested"), firstIdIndex = 2, idCount = 2).ids)
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.readPrimaryRange(index("start"), firstIdIndex = 2, idCount = 2) })
    }

    private fun index(url: String) = CompiledIndex(key = url, term = null, newestUrl = url, sortedUrl = url)

    private fun partial(startByte: Int, totalBytes: Int, vararg ids: Int): SourceByteResponse {
        val body = ByteBuffer.allocate(ids.size * Int.SIZE_BYTES).apply { ids.forEach(::putInt) }.array()
        val range = "bytes $startByte-${startByte + body.size - 1}/$totalBytes"
        return SourceByteResponse(206, body, mapOf("Content-Range" to listOf(range)))
    }

    private suspend fun failureOf(read: suspend () -> Any): SourceFailureReason? =
        (runCatching { read() }.exceptionOrNull() as? SourceAdapterException)?.reason
}
