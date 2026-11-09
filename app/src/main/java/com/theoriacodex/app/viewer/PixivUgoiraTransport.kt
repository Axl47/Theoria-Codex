package com.theoriacodex.app.viewer

import com.theoriacodex.app.network.AppHttpClients
import com.theoriacodex.sources.http.await
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/** Each request is cancelled when the last animation consumer leaves. */
internal class PixivUgoiraTransport(private val client: OkHttpClient = AppHttpClients.downloads) {
    suspend fun fetchMetadata(postId: String, accessToken: String): TextResponse {
        val url = "${PIXIV_API_BASE}/v1/ugoira/metadata?illust_id=$postId"
        return client.newCall(request(url, accessToken)).await { response ->
            TextResponse(statusCode = response.code, body = response.body.string())
        }
    }

    suspend fun downloadZip(
        url: String,
        accessToken: String,
        destination: File,
    ): BinaryResponse {
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        return client.newCall(request(url, accessToken)).await { response ->
            try {
                if (response.isSuccessful) {
                    copyArchiveResponse(response.body.byteStream(), temporary)
                    publishArchive(temporary, destination)
                }
                BinaryResponse(statusCode = response.code)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun copyArchiveResponse(body: InputStream, temporary: File) {
        body.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(UGOIRA_DOWNLOAD_BUFFER_BYTES)
                var written = 0L
                while (true) {
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

    private fun request(url: String, accessToken: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .header("Referer", "https://www.pixiv.net/")
        .header("User-Agent", "Mozilla/5.0")
        .build()
}

private const val PIXIV_API_BASE = "https://app-api.pixiv.net"
private const val UGOIRA_DOWNLOAD_BUFFER_BYTES = 64 * 1024
