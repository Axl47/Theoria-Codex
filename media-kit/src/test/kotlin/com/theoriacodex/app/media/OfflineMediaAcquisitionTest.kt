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
    fun `streaming uses request headers and verifies complete bytes (ADR 0110)`() = runTest {
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
    fun `partial responses oversized files and provider error pages are rejected (ADR 0110)`() = runTest {
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

    @Test
    fun `a file over the byte limit, declared or not, and a partial-content answer are rejected (ADR 0110)`() = runTest {
        val overLimit = runCatching {
            downloadMediaFile("https://example.test/image.png", emptyMap(), temporary.newFile(), maxBytes = PNG_BYTES.size - 1L, client = FakeServer().client)
        }
        val partialContent = runCatching {
            downloadMediaFile("https://example.test/image.png", emptyMap(), temporary.newFile(), client = FakeServer(status = 206).client)
        }
        val atLimit = runCatching {
            downloadMediaFile("https://example.test/image.png", emptyMap(), temporary.newFile(), maxBytes = PNG_BYTES.size.toLong(), client = FakeServer().client)
        }
        val undeclaredOverLimit = runCatching {
            downloadMediaFile("https://example.test/image.png", emptyMap(), temporary.newFile(), maxBytes = PNG_BYTES.size - 1L, client = FakeServer(length = -1).client)
        }
        val undeclaredAtLimit = runCatching {
            downloadMediaFile("https://example.test/image.png", emptyMap(), temporary.newFile(), maxBytes = PNG_BYTES.size.toLong(), client = FakeServer(length = -1).client)
        }

        assertTrue(overLimit.isFailure)
        assertTrue(partialContent.isFailure)
        assertTrue(atLimit.isSuccess)
        assertTrue(undeclaredOverLimit.isFailure)
        assertTrue(undeclaredAtLimit.isSuccess)
    }

    @Test
    fun `a file declared one byte over 2 GiB is refused before anything is written, and one of exactly 2 GiB is not (ADR 0110)`() = runTest {
        val gibibytes = 2L * 1024 * 1024 * 1024
        val over = temporary.newFile()
        val exact = temporary.newFile()

        assertTrue(runCatching { downloadMediaFile("https://example.test/a.png", emptyMap(), over, client = FakeServer(length = gibibytes + 1).client) }.isFailure)
        assertTrue(runCatching { downloadMediaFile("https://example.test/b.png", emptyMap(), exact, client = FakeServer(length = gibibytes).client) }.isFailure)

        assertEquals(0L, over.length())
        assertEquals(PNG_BYTES.size.toLong(), exact.length())
    }

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
