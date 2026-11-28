package com.theoriacodex.app.media

import com.theoriacodex.sources.http.await
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request

suspend fun downloadMediaFileInParts(
    url: String,
    headers: Map<String, String>,
    destination: File,
    maxBytes: Long,
    client: OkHttpClient,
) {
    try {
        coroutineScope { fetchInParts(this, client, mediaRequest(url, headers), destination, maxBytes) }
    } catch (_: MediaPartFailure) {
        downloadMediaFile(url, headers, destination, maxBytes, client)
    }
}

internal fun mediaParts(length: Long, acceptRanges: String?): List<LongRange>? {
    if (length < MIN_PARTED_MEDIA_BYTES || acceptRanges?.trim()?.equals("bytes", ignoreCase = true) != true) return null
    val size = (length + MEDIA_PARTS - 1) / MEDIA_PARTS
    return (0 until MEDIA_PARTS).map { index -> index * size..minOf(length, (index + 1) * size) - 1 }
}

private class PartsInFlight(val length: Long, val first: Long, val rest: List<Deferred<Long>>)

private suspend fun fetchInParts(scope: CoroutineScope, client: OkHttpClient, request: Request, destination: File, maxBytes: Long) {
    val inFlight = client.newCall(request).await { response ->
        val length = checkedMediaLength(response, maxBytes)
        val parts = mediaParts(length, response.header("Accept-Ranges"))
        if (parts == null) {
            writeWholeMedia(response, destination, maxBytes, length)
            return@await null
        }
        RandomAccessFile(destination, "rw").use { file -> file.setLength(length) }
        val rest = parts.drop(1).map { part -> scope.async(Dispatchers.IO) { fetchPart(client, request, part, length, destination) } }
        PartsInFlight(length, asPartFailure { writePart(response.body.byteStream(), destination, parts.first()) }, rest)
    } ?: return
    val written = inFlight.first + inFlight.rest.awaitAll().sum()
    if (written != inFlight.length) throw MediaPartFailure()
    validateOfflineMediaFile(destination)
}

private suspend fun fetchPart(client: OkHttpClient, request: Request, part: LongRange, length: Long, destination: File): Long =
    asPartFailure {
        client.newCall(request.newBuilder().header("Range", "bytes=${part.first}-${part.last}").build()).await { response ->
            val range = response.header("Content-Range")?.trim()
            if (response.code != HTTP_PARTIAL_CONTENT || range != "bytes ${part.first}-${part.last}/$length") throw MediaPartFailure()
            writePart(response.body.byteStream(), destination, part)
        }
    }

private fun writePart(input: InputStream, destination: File, part: LongRange): Long {
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
    return written
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
