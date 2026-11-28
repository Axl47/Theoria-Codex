package com.theoriacodex.app.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaPartsDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a large file from a server that takes ranges arrives in four parallel parts with the page's headers (ADR 0225)`() = runTest {
        val server = RangeServer(gifBytes(LARGE))
        val output = temporary.newFile()

        downloadMediaFileInParts(URL, mapOf("Referer" to "https://provider.test/"), output, Long.MAX_VALUE, server.client)

        assertArrayEquals(server.bytes, output.readBytes())
        assertTrue("the whole-file answer waited for every range to be asked", server.rangesArrivedTogether)
        val quarter = LARGE / 4
        assertEquals(
            listOf(null, "bytes=$quarter-${2 * quarter - 1}", "bytes=${2 * quarter}-${3 * quarter - 1}", "bytes=${3 * quarter}-${LARGE - 1}"),
            server.requests.map { it.header("Range") }.sortedBy { it.orEmpty() },
        )
        assertTrue(server.requests.all { it.header("Referer") == "https://provider.test/" })
    }

    @Test
    fun `a range answered with the whole file or the wrong span falls back to one whole download (ADR 0225)`() = runTest {
        listOf(RangeAnswer.WHOLE_FILE, RangeAnswer.WRONG_SPAN).forEach { answer ->
            val server = RangeServer(gifBytes(LARGE), rangeAnswer = answer, waitForRanges = false)
            val output = temporary.newFile()

            downloadMediaFileInParts(URL, emptyMap(), output, Long.MAX_VALUE, server.client)

            assertArrayEquals(answer.name, server.bytes, output.readBytes())
            assertEquals(answer.name, 2, server.requests.count { it.header("Range") == null })
        }
    }

    @Test
    fun `a small file or a server without ranges is fetched in one request (ADR 0225)`() = runTest {
        listOf(RangeServer(gifBytes(LARGE / 8)), RangeServer(gifBytes(LARGE), acceptsRanges = false)).forEach { server ->
            val output = temporary.newFile()

            downloadMediaFileInParts(URL, emptyMap(), output, Long.MAX_VALUE, server.client)

            assertArrayEquals(server.bytes, output.readBytes())
            assertEquals(1, server.requests.size)
        }
    }

    private enum class RangeAnswer { PARTIAL, WHOLE_FILE, WRONG_SPAN }

    private class RangeServer(
        val bytes: ByteArray,
        private val acceptsRanges: Boolean = true,
        private val rangeAnswer: RangeAnswer = RangeAnswer.PARTIAL,
        private val waitForRanges: Boolean = acceptsRanges && bytes.size == LARGE,
    ) {
        val requests: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf())
        private val ranges = CountDownLatch(3)
        var rangesArrivedTogether = false

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val range = request.header("Range")?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
            if (range == null) {
                respond(request, 200, bytes, held = waitForRanges)
            } else {
                ranges.countDown()
                val (start, end) = range
                when (rangeAnswer) {
                    RangeAnswer.PARTIAL -> respond(request, 206, bytes.copyOfRange(start, end + 1), "bytes $start-$end/${bytes.size}")
                    RangeAnswer.WHOLE_FILE -> respond(request, 200, bytes)
                    RangeAnswer.WRONG_SPAN -> respond(request, 206, bytes.copyOfRange(0, end - start + 1), "bytes 0-${end - start}/${bytes.size}")
                }
            }
        }.build()

        private fun respond(request: Request, code: Int, body: ByteArray, contentRange: String? = null, held: Boolean = false): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .header("Content-Type", "image/gif")
                .apply { if (acceptsRanges) header("Accept-Ranges", "bytes") }
                .apply { contentRange?.let { header("Content-Range", it) } }
                .body(if (held) heldUntilRanges(body) else body.toResponseBody("image/gif".toMediaType()))
                .build()

        private fun heldUntilRanges(body: ByteArray): ResponseBody {
            val source = object : ForwardingSource(Buffer().write(body)) {
                private var waited = false

                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!waited) {
                        waited = true
                        rangesArrivedTogether = ranges.await(5, TimeUnit.SECONDS)
                    }
                    return super.read(sink, byteCount)
                }
            }.buffer()
            return source.asResponseBody("image/gif".toMediaType(), body.size.toLong())
        }
    }

    private companion object {
        const val URL = "https://example.test/large.gif"
        const val LARGE = 4 * 1024 * 1024

        fun gifBytes(size: Int): ByteArray = Random(size).nextBytes(size).also { bytes ->
            "GIF89a".toByteArray().copyInto(bytes)
        }
    }
}
