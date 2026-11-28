package com.theoriacodex.app.media

import com.theoriacodex.app.network.recordingRateLimits
import com.theoriacodex.sources.http.HostOutageTracker
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaPartsDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a large file from a server that takes ranges arrives in four parallel parts with the page's headers (ADR 0225)`() = runTest {
        val server = RangeServer(gifBytes(LARGE), firstBody = Hold.UNTIL_RANGES_ASKED)
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
    fun `a range answered with the whole file, the wrong span, a short body or a 429 falls back to one whole download (ADR 0225)`() = runTest {
        RangeAnswer.entries.filterNot { it == RangeAnswer.PARTIAL }.forEach { answer ->
            val outages = HostOutageTracker()
            val server = RangeServer(gifBytes(LARGE), rangeAnswer = answer, outages = outages)
            val output = temporary.newFile()

            downloadMediaFileInParts(URL, emptyMap(), output, Long.MAX_VALUE, server.client)

            assertArrayEquals(answer.name, server.bytes, output.readBytes())
            assertEquals(answer.name, 2, server.requests.count { it.header("Range") == null })
            assertFalse(answer.name, outages.isSiteRateLimited(URL))
        }
    }

    @Test
    fun `a small file, a server without ranges or an HTTP 2 host is fetched in one request (ADR 0225)`() = runTest {
        val servers = listOf(
            RangeServer(gifBytes(LARGE / 8)),
            RangeServer(gifBytes(LARGE), acceptsRanges = false),
            RangeServer(gifBytes(LARGE), protocol = Protocol.HTTP_2),
        )
        servers.forEach { server ->
            val output = temporary.newFile()

            downloadMediaFileInParts(URL, emptyMap(), output, Long.MAX_VALUE, server.client)

            assertArrayEquals(server.bytes, output.readBytes())
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `cancelling the download cancels every part (ADR 0225)`() = runBlocking {
        val server = RangeServer(gifBytes(LARGE), firstBody = Hold.UNTIL_CANCELLED, rangeBodies = Hold.UNTIL_CANCELLED)
        val download = CoroutineScope(Dispatchers.IO).launch {
            downloadMediaFileInParts(URL, emptyMap(), temporary.newFile(), Long.MAX_VALUE, server.client)
        }
        assertTrue(server.ranges.await(5, TimeUnit.SECONDS))

        withTimeout(5_000L) { download.cancelAndJoin() }

        assertEquals(4, server.calls.size)
        assertTrue(server.calls.all(Call::isCanceled))
    }

    private enum class RangeAnswer { PARTIAL, WHOLE_FILE, WRONG_SPAN, SHORT_BODY, TOO_MANY_REQUESTS }

    private enum class Hold { NONE, UNTIL_RANGES_ASKED, UNTIL_CANCELLED }

    private class RangeServer(
        val bytes: ByteArray,
        private val acceptsRanges: Boolean = true,
        private val protocol: Protocol = Protocol.HTTP_1_1,
        private val rangeAnswer: RangeAnswer = RangeAnswer.PARTIAL,
        private val firstBody: Hold = Hold.NONE,
        private val rangeBodies: Hold = Hold.NONE,
        outages: HostOutageTracker = HostOutageTracker(),
    ) {
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        val ranges = CountDownLatch(3)
        @Volatile var rangesArrivedTogether = false

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(recordingRateLimits(outages))
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                calls += chain.call()
                val range = request.header("Range")?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
                if (range == null) return@addInterceptor respond(chain.call(), request, 200, bytes, hold = firstBody)
                ranges.countDown()
                val (start, end) = range
                when (rangeAnswer) {
                    RangeAnswer.PARTIAL -> respond(chain.call(), request, 206, bytes.copyOfRange(start, end + 1), "bytes $start-$end/${bytes.size}", rangeBodies)
                    RangeAnswer.WHOLE_FILE -> respond(chain.call(), request, 200, bytes)
                    RangeAnswer.WRONG_SPAN -> respond(chain.call(), request, 206, bytes.copyOfRange(0, end - start + 1), "bytes 0-${end - start}/${bytes.size}")
                    RangeAnswer.SHORT_BODY -> respond(chain.call(), request, 206, bytes.copyOfRange(start, end), "bytes $start-$end/${bytes.size}")
                    RangeAnswer.TOO_MANY_REQUESTS -> respond(chain.call(), request, 429, ByteArray(0))
                }
            }
            .build()

        private fun respond(call: Call, request: Request, code: Int, body: ByteArray, contentRange: String? = null, hold: Hold = Hold.NONE): Response =
            Response.Builder()
                .request(request)
                .protocol(protocol)
                .code(code)
                .message("")
                .header("Content-Type", "image/gif")
                .apply { if (acceptsRanges) header("Accept-Ranges", "bytes") }
                .apply { contentRange?.let { header("Content-Range", it) } }
                .body(heldBody(call, body, hold))
                .build()

        private fun heldBody(call: Call, body: ByteArray, hold: Hold): ResponseBody {
            val source = object : ForwardingSource(Buffer().write(body)) {
                private var released = hold == Hold.NONE

                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!released) release()
                    return super.read(sink, byteCount)
                }

                private fun release() {
                    released = true
                    when (hold) {
                        Hold.NONE -> Unit
                        Hold.UNTIL_RANGES_ASKED -> rangesArrivedTogether = ranges.await(5, TimeUnit.SECONDS)
                        Hold.UNTIL_CANCELLED -> {
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            while (!call.isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                            throw IOException("Canceled")
                        }
                    }
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
