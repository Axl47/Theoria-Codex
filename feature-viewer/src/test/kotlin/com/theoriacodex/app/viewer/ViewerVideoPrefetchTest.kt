package com.theoriacodex.app.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoQuality
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ViewerVideoPrefetchTest.PlayerApplication::class)
class ViewerVideoPrefetchTest {
    class PlayerApplication : Application(), VideoPlaybackInfrastructureOwner {
        override val videoPlaybackInfrastructure by lazy { VideoPlaybackInfrastructure(this) }
    }

    @Test
    fun `warmed bytes are explicit and skipped or failed work cannot claim cache data`() {
        assertEquals(
            ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
            ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED, bytesCached = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED, bytesCached = 1L)
        }
    }

    @Test
    fun `a Viewer prefetch warms the shared cache entry that the page's player reads`() = runBlocking {
        val bytes = ByteArray(64 * 1024) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/clip.mp4") { exchange ->
                exchange.responseHeaders.add("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val location = "http://127.0.0.1:${server.address.port}/clip.mp4"
            val ref = ImageRef(location, null, "video/mp4")
            val media = ViewerMediaState(ViewerMediaKey(PostId(SourceKey.GELBOORU, "clip"), 0), ref, ViewerMediaKind.VIDEO, location)
            val context = ApplicationProvider.getApplicationContext<PlayerApplication>()

            assertEquals(ViewerPrefetchOutcome.WARMED, prefetchViewerMedia(context, media, VideoQuality.AUTO).outcome)

            val playback = requireNotNull(ref.viewerPlayback(SourceKey.GELBOORU, VideoQuality.AUTO, metered = false))
            assertTrue(context.videoPlaybackInfrastructure.cachedBytes(playback.location, playback.headers) > 0L)
        } finally {
            server.stop(0)
        }
    }
}
