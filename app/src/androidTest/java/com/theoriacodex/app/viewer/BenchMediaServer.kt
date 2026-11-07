package com.theoriacodex.app.viewer

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * A throttled loopback HTTP/1.1 file server for [MediaBufferingBenchmarkTest].
 *
 * It runs in the app process so loopback socket buffers, not a USB bridge, bound how far a response can run
 * ahead of the app reading it, much as a phone's TCP receive window does. One token bucket models a shared
 * link: concurrent responses split [bytesPerSecond]. Each response waits [ttfbMs] before its headers. Files
 * carry `ETag` and `Last-Modified` but no `Cache-Control`, as an nginx origin does by default, and conditional
 * requests get 304. Every byte written is recorded per path as a [start, end) interval, so a snapshot reports
 * total, unique and duplicate (re-downloaded) bytes, including responses still in flight.
 */
internal class BenchMediaServer(
    private val root: File,
    bytesPerSecond: Long,
    private val ttfbMs: Long,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val bucket = TokenBucket(bytesPerSecond)
    private val responses = ConcurrentHashMap<String, CopyOnWriteArrayList<Response>>()
    private val workers = Executors.newCachedThreadPool()
    private val acceptor = thread(name = "media-bench-accept", isDaemon = true) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            }
            workers.execute { serve(socket) }
        }
    }

    val baseUrl = "http://127.0.0.1:${server.localPort}"

    fun reset() = responses.clear()

    /**
     * Every response for [path] opened at or after [sinceMs] (elapsed realtime): when its headers were sent, its
     * first byte, bytes written so far, and when it finished or was abandoned (or -1 while still open).
     */
    fun timeline(path: String, sinceMs: Long): JSONArray = JSONArray().apply {
        responses[path].orEmpty().filter { it.openedAtMs >= sinceMs }.forEach { response ->
            put(JSONObject().put("at", response.openedAtMs - sinceMs).put("offset", response.start)
                .put("bytes", response.sent.get()).put("endedAt", response.endedAtMs.let { if (it < 0) -1 else it - sinceMs }))
        }
    }

    /** Per path: requests, bytes written, unique bytes and duplicate bytes. */
    fun snapshot(): JSONObject = JSONObject().apply {
        responses.forEach { (path, list) ->
            val sent = list.sumOf { it.sent.get() }
            val unique = unionLength(list.map { it.start to it.start + it.sent.get() })
            put(path, JSONObject()
                .put("requests", list.size)
                .put("notModified", list.count { it.notModified })
                .put("bytes", sent)
                .put("uniqueBytes", unique)
                .put("duplicateBytes", sent - unique))
        }
    }

    override fun close() {
        server.close()
        workers.shutdownNow()
        acceptor.join(CLOSE_TIMEOUT_MS)
    }

    private fun serve(socket: Socket) = socket.use {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        try {
            while (true) {
                val request = readRequest(input) ?: return
                respond(request, output)
            }
        } catch (_: IOException) {
            // The client closed or abandoned the connection; the bytes written so far stay recorded.
        }
    }

    private fun respond(request: Request, output: OutputStream) {
        val path = request.target.substringBefore('?')
        val file = File(root, path.removePrefix("/")).canonicalFile
        if (!file.path.startsWith(root.canonicalPath) || !file.isFile) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val size = file.length()
        val etag = "\"$size-${file.lastModified()}\""
        Thread.sleep(ttfbMs)
        if (request.headers["if-none-match"] == etag) {
            responses.getOrPut(path) { CopyOnWriteArrayList() } += Response(0L, notModified = true)
            output.write("HTTP/1.1 304 Not Modified\r\nETag: $etag\r\nContent-Length: 0\r\n\r\n".toByteArray())
            output.flush()
            return
        }
        val range = request.headers["range"]?.let { parseRange(it, size) }
        val start = range?.first ?: 0L
        val end = range?.second ?: (size - 1)
        val length = end - start + 1
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: ${contentType(file.name)}\r\n")
            append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("ETag: $etag\r\n")
            append("Last-Modified: ${httpDate(file.lastModified())}\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        val response = Response(start).also { responses.getOrPut(path) { CopyOnWriteArrayList() } += it }
        try {
            RandomAccessFile(file, "r").use { handle ->
                handle.seek(start)
                val buffer = ByteArray(CHUNK_BYTES)
                var remaining = length
                while (remaining > 0) {
                    val read = handle.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    bucket.take(read)
                    output.write(buffer, 0, read)
                    response.sent.addAndGet(read.toLong())
                    remaining -= read
                }
            }
            output.flush()
        } finally {
            response.endedAtMs = SystemClock.elapsedRealtime()
        }
    }

    private class Request(val target: String, val headers: Map<String, String>)

    private class Response(val start: Long, val notModified: Boolean = false) {
        val sent = AtomicLong()
        val openedAtMs = SystemClock.elapsedRealtime()
        @Volatile var endedAtMs = -1L
    }

    /** One link shared by every response. */
    private class TokenBucket(private val bytesPerSecond: Long) {
        private val capacity = maxOf(CHUNK_BYTES * 4L, bytesPerSecond / BURST_DIVISOR)
        private var tokens = capacity.toDouble()
        private var updatedNs = System.nanoTime()

        fun take(count: Int) {
            while (true) {
                val waitMs = synchronized(this) {
                    val now = System.nanoTime()
                    tokens = minOf(capacity.toDouble(), tokens + (now - updatedNs) / 1e9 * bytesPerSecond)
                    updatedNs = now
                    if (tokens >= count) {
                        tokens -= count
                        return
                    }
                    ((count - tokens) * 1_000 / bytesPerSecond).toLong().coerceIn(1L, MAX_WAIT_MS)
                }
                Thread.sleep(waitMs)
            }
        }
    }

    private companion object {
        const val BACKLOG = 64
        const val CHUNK_BYTES = 16 * 1024
        const val BURST_DIVISOR = 20L
        const val MAX_WAIT_MS = 50L
        const val CLOSE_TIMEOUT_MS = 5_000L

        fun readRequest(input: InputStream): Request? {
            val requestLine = readLine(input) ?: return null
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            return Request(requestLine.split(' ').getOrElse(1) { "/" }, headers)
        }

        fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (line.isEmpty()) null else line.toString()
                if (byte == '\n'.code) return line.toString().trimEnd('\r')
                line.append(byte.toChar())
            }
        }

        /** `bytes=a-b`, `bytes=a-` or `bytes=-n`, clamped to the file; null when absent or unsatisfiable. */
        fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            val spec = header.removePrefix("bytes=").takeIf { it != header } ?: return null
            val (first, last) = spec.split('-', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            val range = when {
                first.isNotEmpty() -> first.toLong() to (last.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
                last.isNotEmpty() -> (size - last.toLong()).coerceAtLeast(0L) to size - 1
                else -> return null
            }
            return range.takeIf { it.first <= it.second }
        }

        fun unionLength(intervals: List<Pair<Long, Long>>): Long {
            var total = 0L
            var currentStart = -1L
            var currentEnd = -1L
            intervals.filter { it.second > it.first }.sortedBy { it.first }.forEach { (start, end) ->
                if (start > currentEnd) {
                    if (currentEnd > currentStart) total += currentEnd - currentStart
                    currentStart = start
                    currentEnd = end
                } else {
                    currentEnd = maxOf(currentEnd, end)
                }
            }
            if (currentEnd > currentStart) total += currentEnd - currentStart
            return total
        }

        fun contentType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "mp4" -> "video/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }

        fun httpDate(epochMs: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(epochMs))
    }
}
