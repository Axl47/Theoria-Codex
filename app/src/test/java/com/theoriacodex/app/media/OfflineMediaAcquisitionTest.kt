package com.theoriacodex.app.media

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMediaAcquisitionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `streaming uses request headers and verifies complete bytes`() = runTest {
        val server = FakeServer()
        val output = temporary.newFile()
        downloadMediaFile(
            "https://example.test/image.png", mapOf("Referer" to "https://provider.test/"), output, client = server.client,
        )
        assertTrue(output.readBytes().contentEquals(PNG_BYTES))
        assertEquals("https://provider.test/", server.request?.header("Referer"))
        assertEquals("identity", server.request?.header("Accept-Encoding"))
    }

    @Test
    fun `partial responses oversized files and provider error pages are rejected`() = runTest {
        val invalid = listOf(
            FakeServer(length = 20L),
            FakeServer(status = 403),
            FakeServer(type = "text/html"),
            FakeServer(bytes = "<html>error</html>".toByteArray(), type = "application/octet-stream"),
            FakeServer(length = 3L * 1024 * 1024 * 1024),
            FakeServer(bytes = byteArrayOf(), length = 0L),
        )
        for (server in invalid) {
            val result = runCatching {
                downloadMediaFile("https://example.test/image.png", emptyMap(), temporary.newFile(), client = server.client)
            }
            assertTrue(result.isFailure)
        }
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    }

    /** Answers every request itself; [length] may disagree with [bytes], as a truncated response does. */
    private class FakeServer(
        private val bytes: ByteArray = PNG_BYTES,
        private val length: Long = bytes.size.toLong(),
        private val status: Int = 200,
        private val type: String = "image/png",
    ) {
        var request: Request? = null

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .header("Content-Type", type)
                .body(
                    object : ResponseBody() {
                        override fun contentType(): MediaType = type.toMediaType()
                        override fun contentLength(): Long = length
                        override fun source(): BufferedSource = Buffer().write(bytes)
                    },
                )
                .build()
        }.build()
    }
}
