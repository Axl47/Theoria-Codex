package com.theoriacodex.app.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.toViewerMediaState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import com.theoriacodex.domain.model.VideoVariant
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerVideoPrefetchDeviceTest {
    /** The real transport against loopback: the bytes fetched must be the rendition the Viewer would play. */
    @Test
    fun prefetchRequestsTheRenditionPlaybackWillUse() = LoopbackVideo().use { server ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.theoriacodex.debug")
        val ref = ImageRef(
            url = server.url("best.mp4"), localPath = null, mime = "video/mp4",
            videoVariants = listOf(
                VideoVariant(server.url("best.mp4"), height = 1080),
                VideoVariant(server.url("small.mp4"), height = 360),
            ),
        )
        val post = Post(
            PostId(SourceKey.RULE34VIDEO, "prefetch"), ref, ref, pageUrl = null, width = 100, height = 100,
            canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = 1L, media = listOf(ref),
        )

        val result = runBlocking { prefetchViewerMedia(context, ref.toViewerMediaState(post, 0), VideoQuality.DATA_SAVER) }

        assertEquals(ViewerPrefetchOutcome.WARMED, result.outcome)
        assertEquals(setOf("/small.mp4"), server.requestedPaths.toSet())
    }
}

private class LoopbackVideo : AutoCloseable {
    val requestedPaths = CopyOnWriteArrayList<String>()
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val worker = thread(name = "prefetch-fixture", isDaemon = true) {
        try {
            while (!server.isClosed) server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader()
                requestedPaths += input.readLine().split(' ')[1]
                while (!input.readLine().isNullOrEmpty()) Unit
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n".toByteArray())
                    write(ByteArray(1024))
                    flush()
                }
            }
        } catch (error: SocketException) {
            if (!server.isClosed) throw error
        }
    }

    fun url(name: String) = "http://127.0.0.1:${server.localPort}/$name"

    override fun close() {
        server.close()
        worker.join(5_000)
    }
}
