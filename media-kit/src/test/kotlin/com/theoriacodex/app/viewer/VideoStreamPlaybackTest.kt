@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.app.Application
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.domain.model.MediaTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real Media3 players against [StreamFixtureServer]: what [VideoPlaybackInfrastructure.bind] makes of each request field. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoStreamPlaybackTest {
    private val application = RuntimeEnvironment.getApplication()
    private val server = StreamFixtureServer(
        streamFixtureRoutes(application.assets) + mapOf(
            "/main.wav" to FixtureRoute.Body(wav(channels = 1)),
            "/ja.wav" to FixtureRoute.Body(wav(channels = 1)),
            // Media3 alone would prefer this one, for its extra channel.
            "/en.wav" to FixtureRoute.Body(wav(channels = 2)),
            "/bad.mpd" to FixtureRoute.Body("4f2a9c0e17".toByteArray()),
            "/subtitles/page" to FixtureRoute.Body("<html><body>Not found</body></html>".toByteArray()),
        ),
    )
    private val infrastructure = VideoPlaybackInfrastructure(application)
    private val pool = infrastructure.viewerVideoPlayerPool

    @After fun stopServer() = server.close()

    @Test fun `the mime wins over the address, which decides without one, and only a progressive web file is cached`() {
        fun source(request: VideoPlaybackRequest) = infrastructure.bind(request).run { mediaSourceFactory.createMediaSource(mediaItem) }
        fun cacheKey(request: VideoPlaybackRequest) = infrastructure.bind(request).mediaItem.localConfiguration?.customCacheKey

        assertTrue(source(request("https://cdn.test/hls/master.m3u8?token=a.mp4")) is HlsMediaSource)
        assertTrue(source(request("https://cdn.test/stream", mime = "application/vnd.apple.mpegurl")) is HlsMediaSource)
        assertTrue(source(request("https://cdn.test/manifest.mpd")) is DashMediaSource)
        assertTrue(source(request("https://cdn.test/clip.m3u8", mime = "video/mp4")) is ProgressiveMediaSource)
        assertTrue(source(request("https://cdn.test/stream")) is ProgressiveMediaSource)
        // Formats the app plays as nothing else (SmoothStreaming, RTSP) are progressive files, not a crash here.
        assertTrue(source(request("https://cdn.test/live.isml/manifest")) is ProgressiveMediaSource)
        assertTrue(source(request("https://cdn.test/live", mime = "application/vnd.ms-sstr+xml")) is ProgressiveMediaSource)
        assertTrue(source(request("rtsp://cam.test/stream", mime = "application/x-rtsp")) is ProgressiveMediaSource)
        // An extension's own proxy on the device is an ordinary web address.
        val loopback = request("http://127.0.0.1:8341/clip.mp4", mime = "video/mp4")
        assertEquals("http://127.0.0.1:8341/clip.mp4", infrastructure.bind(loopback).mediaItem.localConfiguration?.uri.toString())
        assertEquals(infrastructure.bind(loopback).identity.cacheKey, cacheKey(loopback))
        val post = request("https://cdn.test/clip", mime = "video/webm")
        assertTrue(source(post) is ProgressiveMediaSource)
        assertEquals(infrastructure.bind(post).identity.cacheKey, cacheKey(post))
        assertNull(cacheKey(request("https://cdn.test/master.m3u8")))
        assertNull(cacheKey(request("https://cdn.test/clip.mp4", cache = false)))
    }

    @Test fun `a slot is reused only for the same request, while the cache key names the bytes alone`() {
        val plain = infrastructure.bind(request("https://cdn.test/clip.mp4")).identity
        val subtitled = infrastructure.bind(request("https://cdn.test/clip.mp4", subtitles = listOf(MediaTrack("https://cdn.test/a.vtt", "A")))).identity

        assertNotEquals(plain, subtitled)
        assertEquals(plain.cacheKey, subtitled.cacheKey)
    }

    @Test fun `an HLS episode sends its headers with every request it causes, across a redirect and relative addresses`() {
        val lease = pool.acquire(
            VideoPlaybackRequest(
                location = "${server.base}/episode?token=1",
                headers = STREAM_FIXTURE_HEADERS,
                mime = "application/vnd.apple.mpegurl",
                subtitles = listOf(MediaTrack("${server.base}/subtitles/en.vtt", "English")),
                audioTracks = listOf(MediaTrack("${server.base}/hls/audio.m3u8", "Japanese")),
                cache = false,
            ),
        )
        val player = lease.player
        until { player.playbackTracks().subtitles.isNotEmpty() && "/hls/audio0.ts" in server.paths() && "/hls/video0.ts" in server.paths() }
        // Only the side-loaded file: HLS no longer lists a closed-caption track its playlist never declared.
        player.selectSubtitle(player.playbackTracks().subtitles.single().id)
        until { "/subtitles/en.vtt" in server.paths() }

        assertNull(player.playerError)
        val expected = listOf(
            "/episode", "/hls/master.m3u8", "/hls/video.m3u8", "/hls/key.bin", "/hls/video0.ts",
            "/hls/audio.m3u8", "/hls/audio0.ts", "/subtitles/en.vtt",
        )
        assertTrue(server.paths().toString(), server.paths().containsAll(expected))
        assertTrue(server.requests.toString(), server.requests.all { it.authorised })
        assertEquals(0L, runBlocking { infrastructure.disposableCacheBytes() })
        pool.recycle(lease)
    }

    @Test fun `an episode outside the cache never writes to it, while a post still does`() {
        val episode = pool.acquire(VideoPlaybackRequest("${server.base}/ja.wav", STREAM_FIXTURE_HEADERS, cache = false))
        until { episode.player.playbackState == Player.STATE_READY }
        assertEquals(0L, runBlocking { infrastructure.disposableCacheBytes() })
        pool.recycle(episode)

        val post = pool.acquire(VideoPlaybackRequest("${server.base}/main.wav", STREAM_FIXTURE_HEADERS))
        until { post.player.playbackState == Player.STATE_READY }
        assertTrue(runBlocking { infrastructure.disposableCacheBytes() } > 0L)
        pool.recycle(post)
    }

    @Test fun `separate audio is merged with its labels, the first plays, and another can be chosen by a lasting id`() {
        val lease = pool.acquire(
            VideoPlaybackRequest(
                "${server.base}/main.wav", STREAM_FIXTURE_HEADERS, cache = false,
                audioTracks = listOf(MediaTrack("${server.base}/ja.wav", "Japanese"), MediaTrack("${server.base}/en.wav", "English")),
            ),
        )
        val player = lease.player
        until { player.playbackTracks().audio.any { it.label == "Japanese" && it.selected } }
        val tracks = player.playbackTracks().audio

        assertEquals(listOf("", "Japanese", "English"), tracks.map { it.label })
        player.selectAudio(tracks.single { it.label == "English" }.id)
        until { player.playbackTracks().audio.single { it.selected }.label == "English" }
        assertEquals(tracks.map { it.id }, player.playbackTracks().audio.map { it.id })
        player.selectAudio("gone/0")
        until { player.playbackTracks().audio.single { it.selected }.label == "English" }
        pool.recycle(lease)
    }

    @Test fun `subtitles stay off and unloaded until chosen, a stale id changes nothing, and null turns them off`() {
        val cues = mutableListOf<String>()
        val lease = pool.acquire(
            VideoPlaybackRequest(
                "${server.base}/main.wav", STREAM_FIXTURE_HEADERS, cache = false,
                subtitles = listOf(MediaTrack("${server.base}/subtitles/en.vtt", "English"), MediaTrack("${server.base}/subtitles/signs.vtt", "Signs")),
            ),
        )
        val player = lease.player
        until { player.playbackTracks().subtitles.size == 2 && player.playbackState == Player.STATE_READY }

        assertEquals(listOf("English" to false, "Signs" to false), player.playbackTracks().subtitles.map { it.label to it.selected })
        assertTrue(server.paths().none { it.startsWith("/subtitles/") })
        player.selectSubtitle(player.playbackTracks().subtitles.single { it.label == "Signs" }.id)
        until { player.playbackTracks().subtitles.single { it.label == "Signs" }.selected }
        // Served at a .vtt address, but its first bytes say it is ASS.
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { cueGroup.cues.mapNotNullTo(cues) { it.text?.toString() } }
        })
        player.play()
        until { "Sniffed cue" in cues }
        // A drag turns the chosen subtitles off, rather than decoding the file again after each of its seeks.
        player.scrubTo(1_000L)
        until { player.playbackTracks().subtitles.none { it.selected } }
        player.endScrub()
        until { player.playbackTracks().subtitles.single { it.label == "Signs" }.selected }
        player.selectSubtitle("gone/0")
        assertTrue(player.playbackTracks().subtitles.single { it.label == "Signs" }.selected)
        player.selectSubtitle(null)
        until { player.playbackTracks().subtitles.none { it.selected } }
        assertTrue(server.paths().none { it == "/subtitles/en.vtt" })
        pool.recycle(lease)
    }

    @Test fun `a dead or unreadable subtitle leaves the video playing`() {
        val lease = pool.acquire(
            VideoPlaybackRequest(
                "${server.base}/main.wav", STREAM_FIXTURE_HEADERS, cache = false,
                subtitles = listOf(MediaTrack("${server.base}/subtitles/missing.vtt", "Dead"), MediaTrack("${server.base}/subtitles/page", "Page")),
            ),
        )
        val player = lease.player
        until { player.playbackTracks().subtitles.size == 2 }
        player.selectSubtitle(player.playbackTracks().subtitles.single { it.label == "Dead" }.id)
        player.play()
        until { "/subtitles/missing.vtt" in server.paths() && player.currentPosition > 300L }
        player.selectSubtitle(player.playbackTracks().subtitles.single { it.label == "Page" }.id)
        until { "/subtitles/page" in server.paths() && player.currentPosition > 900L }

        assertNull(player.playerError)
        pool.recycle(lease)
    }

    @Test fun `a malformed manifest or an RTSP stream fails as an ordinary player error`() {
        val lease = pool.acquire(VideoPlaybackRequest("${server.base}/bad.mpd", STREAM_FIXTURE_HEADERS, cache = false))
        until { lease.player.playerError != null }

        assertEquals(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, lease.player.playerError?.errorCode)
        pool.recycle(lease)
        val rtsp = pool.acquire(VideoPlaybackRequest("rtsp://127.0.0.1:1/stream", emptyMap(), mime = "application/x-rtsp", cache = false))
        until { rtsp.player.playerError != null }
        pool.recycle(rtsp)
    }

    @Test fun `prefetch skips a manifest and an uncached stream without a request, and still warms a post`() = runBlocking {
        val manifest = VideoPlaybackRequest("${server.base}/hls/master.m3u8", STREAM_FIXTURE_HEADERS)
        val uncached = VideoPlaybackRequest("${server.base}/main.wav", STREAM_FIXTURE_HEADERS, cache = false)

        assertEquals(ViewerPrefetchOutcome.SKIPPED, infrastructure.prefetch(manifest).outcome)
        assertEquals(ViewerPrefetchOutcome.SKIPPED, infrastructure.prefetch(uncached).outcome)
        assertTrue(server.paths().isEmpty())
        assertEquals(ViewerPrefetchOutcome.WARMED, infrastructure.prefetch(uncached.copy(cache = true)).outcome)
    }

    private fun request(
        location: String,
        mime: String? = null,
        cache: Boolean = true,
        subtitles: List<MediaTrack> = emptyList(),
    ) = VideoPlaybackRequest(location, emptyMap(), mime = mime, subtitles = subtitles, cache = cache)

    /** Runs the main looper, and with it the player's clock, until [condition] holds. */
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out; requests: ${server.paths()}" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
    }
}

/** Two seconds of 16-bit silence as a WAV file: audio that plays without a codec. */
private fun wav(channels: Int, sampleRate: Int = 8_000): ByteArray {
    val dataBytes = 2 * sampleRate * channels * 2
    return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray()).putInt(16)
        putShort(1).putShort(channels.toShort()).putInt(sampleRate).putInt(sampleRate * channels * 2)
        putShort((channels * 2).toShort()).putShort(16)
        put("data".toByteArray()).putInt(dataBytes)
    }.array()
}
