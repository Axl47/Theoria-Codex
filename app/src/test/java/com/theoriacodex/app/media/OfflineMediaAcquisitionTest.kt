package com.theoriacodex.app.media

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMediaAcquisitionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `streaming uses request headers and verifies complete bytes`() = runTest {
        val connection = FakeConnection()
        val output = temporary.newFile()
        downloadOfflineMedia("https://example.test/image.png", mapOf("Referer" to "https://provider.test/"), output) { connection }
        assertTrue(output.readBytes().contentEquals(PNG_BYTES))
        assertEquals("https://provider.test/", connection.getRequestProperty("Referer"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `partial responses oversized files and provider error pages are rejected`() = runTest {
        val invalid = listOf(
            FakeConnection(length = 20L),
            FakeConnection(status = 403),
            FakeConnection(type = "text/html"),
            FakeConnection(bytes = "<html>error</html>".toByteArray(), type = "application/octet-stream"),
            FakeConnection(length = 3L * 1024 * 1024 * 1024),
            FakeConnection(bytes = byteArrayOf(), length = 0L),
        )
        for (connection in invalid) {
            val result = runCatching {
                downloadOfflineMedia("https://example.test/image.png", emptyMap(), temporary.newFile()) { connection }
            }
            assertTrue(result.isFailure)
            assertTrue(connection.disconnected)
        }
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    }

    private class FakeConnection(
        private val bytes: ByteArray = PNG_BYTES,
        private val length: Long = bytes.size.toLong(),
        private val status: Int = 200,
        private val type: String = "image/png",
    ) : HttpURLConnection(URL("https://example.test/image.png")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status
        override fun getContentType(): String = type
        override fun getHeaderFieldLong(name: String, default: Long): Long = if (name == "Content-Length") length else default
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
}
