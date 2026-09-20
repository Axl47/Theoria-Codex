package com.theoriacodex.app.viewer

import com.theoriacodex.sources.http.executeCancellableHttpConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

/** Request-scoped connections disconnect when the last animation consumer leaves. */
internal class PixivUgoiraTransport {
    suspend fun fetchMetadata(postId: String, accessToken: String): TextResponse {
        val url = "${PIXIV_API_BASE}/v1/ugoira/metadata?illust_id=$postId"
        val connection = openConnection(url, accessToken)
        return executeCancellableHttpConnection(connection) {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            TextResponse(statusCode = status, body = body)
        }
    }

    suspend fun downloadZip(
        url: String,
        accessToken: String,
        destination: File,
    ): BinaryResponse {
        val connection = openConnection(url, accessToken)
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        return executeCancellableHttpConnection(connection) {
            try {
                val status = connection.responseCode
                if (status in 200..299) {
                    copyArchiveResponse(connection, temporary)
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    publishArchive(temporary, destination)
                } else {
                    connection.errorStream?.close()
                }
                BinaryResponse(statusCode = status)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun copyArchiveResponse(connection: HttpURLConnection, temporary: File) {
        connection.inputStream.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(UGOIRA_DOWNLOAD_BUFFER_BYTES)
                var written = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > UGOIRA_MAX_COMPRESSED_BYTES) {
                        throw IOException("Pixiv ugoira archive exceeds compressed-byte limit")
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    private fun publishArchive(temporary: File, destination: File) {
        if (!temporary.renameTo(destination)) {
            throw IOException("Could not publish Pixiv ugoira archive")
        }
    }

    private fun openConnection(url: String, accessToken: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Referer", "https://www.pixiv.net/")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
    }
}

private const val PIXIV_API_BASE = "https://app-api.pixiv.net"
private const val UGOIRA_DOWNLOAD_BUFFER_BYTES = 64 * 1024
