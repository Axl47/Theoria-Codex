package com.theoriacodex.app.media

import com.theoriacodex.app.network.withoutRateLimitRecording
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

internal suspend fun downloadMediaFileInParts(
    url: String,
    headers: Map<String, String>,
    destination: File,
    maxBytes: Long,
    client: OkHttpClient,
) {
    try {
        withContext(Dispatchers.IO) { fetchInParts(client, mediaRequest(url, headers), destination, maxBytes) }
    } catch (_: MediaPartFailure) {
        currentCoroutineContext().ensureActive()
        downloadMediaFile(url, headers, destination, maxBytes, client)
    }
}

private fun mediaParts(length: Long, response: Response): List<LongRange>? {
    val acceptsRanges = response.header("Accept-Ranges")?.trim()?.equals("bytes", ignoreCase = true) == true
    if (length < MIN_PARTED_MEDIA_BYTES || !acceptsRanges || response.protocol != Protocol.HTTP_1_1) return null
    val size = (length + MEDIA_PARTS - 1) / MEDIA_PARTS
    return (0 until MEDIA_PARTS).map { index -> index * size..minOf(length, (index + 1) * size) - 1 }
}

private suspend fun fetchInParts(client: OkHttpClient, request: Request, destination: File, maxBytes: Long) =
    client.withResponse(request) { response, call ->
        val length = checkedMediaLength(response, maxBytes)
        val parts = mediaParts(length, response)
        if (parts == null) {
            writeWholeMedia(response, destination, maxBytes, length)
            return@withResponse
        }
        RandomAccessFile(destination, "rw").use { file -> file.setLength(length) }
        val version = response.header("ETag")?.takeUnless { it.startsWith("W/") }
        val ranged = request.newBuilder()
            .apply { (version ?: response.header("Last-Modified"))?.let { header("If-Range", it) } }
            .withoutRateLimitRecording()
            .build()
        val rest = parts.drop(1).map { part -> async { fetchPart(client, ranged, part, length, version, destination) } }
        asPartFailure { writePart(response.body.byteStream(), destination, parts.first()) }
        call.cancel()
        response.close()
        rest.awaitAll()
        validateOfflineMediaFile(destination)
    }

private suspend fun fetchPart(
    client: OkHttpClient,
    ranged: Request,
    part: LongRange,
    length: Long,
    version: String?,
    destination: File,
) = asPartFailure {
    client.withResponse(ranged.newBuilder().header("Range", "bytes=${part.first}-${part.last}").build()) { response, _ ->
        val matches = response.code == HTTP_PARTIAL_CONTENT &&
            response.header("Content-Range")?.trim() == "bytes ${part.first}-${part.last}/$length" &&
            response.body.contentLength() == part.last - part.first + 1 &&
            (version == null || response.header("ETag") == version)
        if (!matches) throw MediaPartFailure()
        writePart(response.body.byteStream(), destination, part)
    }
}

private fun writePart(input: InputStream, destination: File, part: LongRange) {
    val expected = part.last - part.first + 1
    var written = 0L
    FileChannel.open(destination.toPath(), StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteArray(PART_BUFFER_BYTES)
        while (written < expected) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
            if (count < 0) throw MediaPartFailure()
            val chunk = ByteBuffer.wrap(buffer, 0, count)
            while (chunk.hasRemaining()) channel.write(chunk, part.first + written + chunk.position())
            written += count
        }
    }
}

private inline fun <T> asPartFailure(block: () -> T): T = try {
    block()
} catch (failure: IOException) {
    throw failure as? MediaPartFailure ?: MediaPartFailure(failure)
}

private class MediaPartFailure(cause: IOException? = null) : IOException("A media part could not be fetched", cause)

private const val MEDIA_PARTS = 4
private const val MIN_PARTED_MEDIA_BYTES = 4L * 1024 * 1024
private const val PART_BUFFER_BYTES = 64 * 1024
private const val HTTP_PARTIAL_CONTENT = 206
