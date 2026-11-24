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
import org.junit.Assert.assertTrue
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
        answers["unranged"] = partialWithRange(null, 7, 8, 9)
        answers["untotalled"] = partialWithRange("bytes 0-11/*", 7, 8, 9)

        assertArrayEquals(IntArray(0), reader.membershipFor(index("404")))
        assertArrayEquals(IntArray(0), reader.membershipFor(index("416")))
        assertArrayEquals(intArrayOf(7, 8, 9), reader.membershipFor(index("whole")))
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.membershipFor(index("offset")) })
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.membershipFor(index("short")) })
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.membershipFor(index("unranged")) })
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.membershipFor(index("untotalled")) })
        assertArrayEquals(IntArray(0), reader.readCompleteNozomi(index("404"), maxGalleryIds = 3).ids)
        assertArrayEquals(IntArray(0), reader.readCompleteNozomi(index("416"), maxGalleryIds = 3).ids)
        assertArrayEquals(intArrayOf(7, 8, 9), reader.readCompleteNozomi(index("whole"), maxGalleryIds = 3).ids)
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.readCompleteNozomi(index("whole"), maxGalleryIds = 2) })
    }

    @Test
    fun `a page of the primary index takes a 206 only from the requested offset, and a missing one is empty (ADR 0043)`() = runTest {
        answers["requested"] = partial(8, 16, 3, 4)
        answers["start"] = partial(0, 16, 1, 2)
        answers["unranged"] = partialWithRange(null, 3, 4)
        answers["404"] = SourceByteResponse(404, ByteArray(0))
        answers["416"] = SourceByteResponse(416, ByteArray(0))

        assertArrayEquals(intArrayOf(3, 4), reader.readPrimaryRange(index("requested"), firstIdIndex = 2, idCount = 2).ids)
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.readPrimaryRange(index("start"), firstIdIndex = 2, idCount = 2) })
        assertEquals(SourceFailureReason.PARSE, failureOf { reader.readPrimaryRange(index("unranged"), firstIdIndex = 2, idCount = 2) })
        for (missing in listOf("404", "416")) {
            val page = reader.readPrimaryRange(index(missing), firstIdIndex = 2, idCount = 2)
            assertArrayEquals(missing, IntArray(0), page.ids)
            assertTrue(missing, page.exhausted)
        }
    }

    private fun index(url: String) = CompiledIndex(key = url, term = null, newestUrl = url, sortedUrl = url)

    private fun partial(startByte: Int, totalBytes: Int, vararg ids: Int): SourceByteResponse =
        partialWithRange("bytes $startByte-${startByte + ids.size * Int.SIZE_BYTES - 1}/$totalBytes", *ids)

    private fun partialWithRange(range: String?, vararg ids: Int): SourceByteResponse {
        val body = ByteBuffer.allocate(ids.size * Int.SIZE_BYTES).apply { ids.forEach(::putInt) }.array()
        return SourceByteResponse(206, body, range?.let { mapOf("Content-Range" to listOf(it)) }.orEmpty())
    }

    private suspend fun failureOf(read: suspend () -> Any): SourceFailureReason? =
        (runCatching { read() }.exceptionOrNull() as? SourceAdapterException)?.reason
}
