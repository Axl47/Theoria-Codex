package com.theoriacodex.app.viewer

import android.content.res.AssetManager
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

private const val CONNECTION_TIMEOUT_MS = 5_000

val STREAM_FIXTURE_HEADERS = mapOf("X-Fixture-Token" to "episode-secret")

sealed interface FixtureRoute {
    class Body(val bytes: ByteArray) : FixtureRoute
    class Redirect(val location: String) : FixtureRoute
}

fun streamFixtureRoutes(assets: AssetManager): Map<String, FixtureRoute> {
    val files = requireNotNull(assets.list("stream-fixture")).associate { name ->
        "/hls/$name" to FixtureRoute.Body(assets.open("stream-fixture/$name").use { it.readBytes() })
    }
    return files + mapOf(
        "/episode" to FixtureRoute.Redirect("/hls/master.m3u8"),
        "/hls/audio" to files.getValue("/hls/audio.m3u8"),
        "/subtitles/en.vtt" to files.getValue("/hls/subtitles.vtt"),
        "/subtitles/signs.vtt" to files.getValue("/hls/subtitles-ass"),
    )
}

class StreamFixtureServer(private val routes: Map<String, FixtureRoute>) : Closeable {
    data class Request(val path: String, val authorised: Boolean)

    val requests = CopyOnWriteArrayList<Request>()
    private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "StreamFixtureServer") {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                connection.soTimeout = CONNECTION_TIMEOUT_MS
                thread(isDaemon = true) {
                    try {
                        connection.use(::answer)
                    } catch (expected: IOException) {
                    }
                }
            }
        }
    }

    fun paths(): List<String> = requests.map { it.path }

    private fun answer(connection: Socket) {
        val input = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val target = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
            .associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
        val path = target.substringBefore('?')
        val authorised = STREAM_FIXTURE_HEADERS.all { (name, value) -> headers[name.lowercase()] == value }
        requests += Request(path, authorised)
        val output = connection.getOutputStream()
        fun respond(status: String, extra: String = "", body: ByteArray = ByteArray(0)) {
            output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
        when (val route = routes[path].takeIf { authorised }) {
            null -> respond(if (authorised) "404 Not Found" else "403 Forbidden")
            is FixtureRoute.Redirect -> respond("302 Found", "Location: ${route.location}\r\n")
            is FixtureRoute.Body -> {
                val range = headers["range"]?.removePrefix("bytes=")?.split('-')
                val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
                val end = range?.getOrNull(1)?.toIntOrNull()?.plus(1)?.coerceAtMost(route.bytes.size) ?: route.bytes.size
                val body = route.bytes.copyOfRange(start, end)
                if (range == null) {
                    respond("200 OK", body = body)
                } else {
                    respond("206 Partial Content", "Content-Range: bytes $start-${end - 1}/${route.bytes.size}\r\n", body)
                }
            }
        }
    }

    override fun close() = socket.close()
}
