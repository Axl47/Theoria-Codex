package com.theoriacodex.app.viewer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.SocketException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerVideoPrefetchTest {
    @Test
    fun `large or partial range responses are skipped without reading a body`() {
        val maxBytes = 1_024L

        assertEquals(
            ViewerVideoPrefetchAction.SKIP,
            planViewerVideoPrefetch(
                statusCode = 206,
                contentLength = maxBytes,
                contentRange = "bytes 0-1023/196139272",
                maxBytes = maxBytes,
            ).action,
        )
        assertEquals(
            ViewerVideoPrefetchAction.SKIP,
            planViewerVideoPrefetch(
                statusCode = 206,
                contentLength = 512L,
                contentRange = "bytes 0-511/1024",
                maxBytes = maxBytes,
            ).action,
        )
        assertEquals(
            ViewerVideoPrefetchAction.SKIP,
            planViewerVideoPrefetch(
                statusCode = 200,
                contentLength = maxBytes + 1L,
                contentRange = null,
                maxBytes = maxBytes,
            ).action,
        )
    }

    @Test
    fun `only complete small responses are eligible for the local cache`() {
        val completeRange = planViewerVideoPrefetch(
            statusCode = 206,
            contentLength = 1_024L,
            contentRange = "bytes 0-1023/1024",
            maxBytes = 2_048L,
        )
        val completeFull = planViewerVideoPrefetch(
            statusCode = 200,
            contentLength = 1_024L,
            contentRange = null,
            maxBytes = 2_048L,
        )
        val boundedUnknown = planViewerVideoPrefetch(
            statusCode = 200,
            contentLength = null,
            contentRange = null,
            maxBytes = 2_048L,
        )

        assertEquals(ViewerVideoPrefetchAction.DOWNLOAD_BOUNDED, completeRange.action)
        assertEquals(1_024L, completeRange.expectedBytes)
        assertEquals(ViewerVideoPrefetchAction.DOWNLOAD_BOUNDED, completeFull.action)
        assertEquals(1_024L, completeFull.expectedBytes)
        assertEquals(ViewerVideoPrefetchAction.DOWNLOAD_BOUNDED, boundedUnknown.action)
        assertEquals(null, boundedUnknown.expectedBytes)
    }

    @Test
    fun `bounded copy stops at the limit and never promotes a partial body`() = runTest {
        val output = ByteArrayOutputStream()
        val result = copyViewerVideoBodyBounded(
            input = ByteArrayInputStream(ByteArray(1_025) { 7 }),
            output = output,
            maxBytes = 1_024L,
        )

        assertEquals(ViewerVideoCopyState.LIMIT_EXCEEDED, result.state)
        assertEquals(1_024L, result.bytesWritten)
        assertEquals(1_024, output.size())

        val completeOutput = ByteArrayOutputStream()
        val complete = copyViewerVideoBodyBounded(
            input = ByteArrayInputStream(ByteArray(1_024) { 3 }),
            output = completeOutput,
            maxBytes = 1_024L,
        )
        assertEquals(ViewerVideoCopyState.COMPLETE, complete.state)
        assertEquals(1_024L, complete.bytesWritten)
    }

    @Test
    fun `prefetch provider failures are nonfatal while cancellation propagates`() = runTest {
        assertFalse(
            runNonFatalViewerVideoPrefetch {
                throw SSLPeerUnverifiedException("provider certificate mismatch")
            },
        )
        assertFalse(
            runNonFatalViewerVideoPrefetch {
                throw SocketException("stream reset")
            },
        )

        var cancelled = false
        try {
            runNonFatalViewerVideoPrefetch { throw CancellationException("viewer changed") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `completed local cache wins while absent cache keeps remote streaming`() {
        val remote = "https://streaming.gold-usergeneratedcontent.net/videos/anime.mp4"

        assertEquals(
            "/cache/anime.mp4",
            selectViewerVideoPlaybackLocation(
                localPath = null,
                remoteUrl = remote,
                cachedPath = "/cache/anime.mp4",
                cachedBytes = 1_024L,
            ),
        )
        assertEquals(
            remote,
            selectViewerVideoPlaybackLocation(
                localPath = null,
                remoteUrl = remote,
                cachedPath = "/cache/anime.mp4",
                cachedBytes = 0L,
            ),
        )
        assertEquals(
            "/saved/anime.mp4",
            selectViewerVideoPlaybackLocation(
                localPath = "/saved/anime.mp4",
                remoteUrl = remote,
                cachedPath = "/cache/anime.mp4",
                cachedBytes = 1_024L,
            ),
        )
    }
}
