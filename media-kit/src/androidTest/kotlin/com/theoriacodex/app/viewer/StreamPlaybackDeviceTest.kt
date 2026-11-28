package com.theoriacodex.app.viewer

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.MediaTrack
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun anHlsEpisodePlaysWithItsSeparateAudioAndSubtitles() {
        StreamFixtureServer(streamFixtureRoutes(instrumentation.context.assets)).use { server ->
            val cues = CopyOnWriteArrayList<String>()
            val cueListener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    cueGroup.cues.mapNotNullTo(cues) { it.text?.toString() }
                }
            }
            val lease = onMain {
                deviceTestInfrastructure.viewerVideoPlayerPool.acquire(
                    VideoPlaybackRequest(
                        location = "${server.base}/episode",
                        headers = STREAM_FIXTURE_HEADERS,
                        mime = "application/vnd.apple.mpegurl",
                        subtitles = listOf(MediaTrack("${server.base}/subtitles/en.vtt", "English")),
                        audioTracks = listOf(MediaTrack("${server.base}/hls/audio.m3u8", "Japanese")),
                        cache = false,
                    ),
                    loop = false,
                ).also { it.player.addListener(cueListener) }
            }
            val player = lease.player
            try {
                awaitOnMain { player.playbackState == Player.STATE_READY || player.playerError != null }
                skipIfTheEmulatorDecoderFailed(player)
                onMain {
                    player.selectSubtitle(player.playbackTracks().subtitles.single { it.label == "English" }.id)
                    player.play()
                }
                awaitOnMain { player.currentPosition >= 1_500L || player.playerError != null }
                awaitOnMain { "Fixture cue" in cues || player.playerError != null }
                skipIfTheEmulatorDecoderFailed(player)

                onMain {
                    assertNull(player.playerError)
                    assertTrue(player.currentPosition >= 1_500L)
                    assertEquals(listOf("Japanese"), player.playbackTracks().audio.filter { it.selected }.map { it.label })
                    assertTrue(server.requests.all { it.authorised })
                }
                assertTrue(cues.toString(), "Fixture cue" in cues)
            } finally {
                onMain {
                    player.removeListener(cueListener)
                    deviceTestInfrastructure.viewerVideoPlayerPool.recycle(lease)
                }
            }
        }
    }

    @Test
    fun aPositiveSubtitleDelayShowsCuesLaterAndPlaybackStillEndsWithTheVideo_adr_0224() {
        playWithDelayedSubtitles { player, cues ->
            onMain {
                player.setSubtitleDelay(3_000L)
                player.play()
            }
            awaitOnMain { "Early cue" in cues || player.playerError != null }
            skipIfTheEmulatorDecoderFailed(player)
            val shownAtMs = cues.getValue("Early cue")
            awaitOnMain { player.currentPosition >= 3_900L || player.playerError != null }
            val nearEnd = System.currentTimeMillis()
            awaitOnMain(timeoutMs = 5_000L) { player.playbackState == Player.STATE_ENDED || player.playerError != null }
            val endedAfterMs = System.currentTimeMillis() - nearEnd
            Log.i(DEVICE_LOG_TAG, "delay +3000 ms: cue at 500 ms shown at $shownAtMs ms; ended $endedAfterMs ms after 3900 ms")
            onMain { assertNull(player.playerError) }
            assertTrue("shown at $shownAtMs", shownAtMs in 3_300L..3_900L)
            assertTrue("ended after $endedAfterMs", endedAfterMs < 1_000L)
        }
    }

    @Test
    fun aNegativeSubtitleDelayChosenDuringPlaybackShowsCuesEarlier_adr_0224() {
        playWithDelayedSubtitles { player, cues ->
            onMain { player.play() }
            awaitOnMain { player.currentPosition >= 300L || player.playerError != null }
            skipIfTheEmulatorDecoderFailed(player)
            onMain { player.setSubtitleDelay(-1_000L) }
            awaitOnMain { "Late cue" in cues || player.playerError != null }
            val shownAtMs = cues.getValue("Late cue")
            Log.i(DEVICE_LOG_TAG, "delay -1000 ms set at 300 ms: cue at 2000 ms shown at $shownAtMs ms")
            onMain { assertNull(player.playerError) }
            assertTrue("shown at $shownAtMs", shownAtMs in 800L..1_400L)
        }
    }

    @Test
    fun aDelayChangeDuringPlaybackReloadsPassedCuesWithoutSeekingOrRebuffering_adr_0224() {
        playWithDelayedSubtitles { player, _ ->
            val earlyShownAt = CopyOnWriteArrayList<Long>()
            val interruptions = CopyOnWriteArrayList<String>()
            val listener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    if (cueGroup.cues.any { it.text?.toString() == "Early cue" }) earlyShownAt += player.currentPosition
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) interruptions += "buffering at ${player.currentPosition}"
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    interruptions += "discontinuity $reason at ${oldPosition.positionMs}"
                }
            }
            onMain {
                player.addListener(listener)
                player.play()
            }
            awaitOnMain { player.currentPosition >= 1_100L || player.playerError != null }
            skipIfTheEmulatorDecoderFailed(player)
            onMain { player.setSubtitleDelay(1_500L) }
            awaitOnMain { earlyShownAt.size >= 2 || player.playerError != null }
            onMain { player.removeListener(listener) }
            Log.i(DEVICE_LOG_TAG, "delay +1500 ms set at 1100 ms: early cue shown at $earlyShownAt; interruptions $interruptions")
            onMain { assertNull(player.playerError) }
            assertTrue("shown at $earlyShownAt", earlyShownAt[1] in 1_800L..2_400L)
            assertTrue(interruptions.toString(), interruptions.isEmpty())
        }
    }

    private fun playWithDelayedSubtitles(check: (androidx.media3.exoplayer.ExoPlayer, Map<String, Long>) -> Unit) {
        val routes = streamFixtureRoutes(instrumentation.context.assets) + ("/subtitles/timed.vtt" to FixtureRoute.Body(TIMED_CUES.toByteArray()))
        StreamFixtureServer(routes).use { server ->
            val cues = java.util.concurrent.ConcurrentHashMap<String, Long>()
            lateinit var player: androidx.media3.exoplayer.ExoPlayer
            val cueListener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    cueGroup.cues.mapNotNull { it.text?.toString() }.forEach { cues.putIfAbsent(it, player.currentPosition) }
                }
            }
            val lease = onMain {
                deviceTestInfrastructure.viewerVideoPlayerPool.acquire(
                    VideoPlaybackRequest(
                        location = "${server.base}/episode",
                        headers = STREAM_FIXTURE_HEADERS,
                        mime = "application/vnd.apple.mpegurl",
                        subtitles = listOf(MediaTrack("${server.base}/subtitles/timed.vtt", "English")),
                        cache = false,
                    ),
                    loop = false,
                ).also { player = it.player; it.player.addListener(cueListener) }
            }
            try {
                awaitOnMain { player.playbackState == Player.STATE_READY || player.playerError != null }
                skipIfTheEmulatorDecoderFailed(player)
                onMain { player.selectSubtitle(player.playbackTracks().subtitles.single { it.label == "English" }.id) }
                check(player, cues)
            } finally {
                onMain {
                    player.removeListener(cueListener)
                    deviceTestInfrastructure.viewerVideoPlayerPool.recycle(lease)
                }
            }
        }
    }

    @Test
    fun aLoopingVideoKeepsPlayingAfterASeek() {
        val clip = instrumentation.context.assets.open("looping-clip.mp4").use { it.readBytes() }
        StreamFixtureServer(mapOf("/clip.mp4" to FixtureRoute.Body(clip))).use { server ->
            val lease = onMain {
                deviceTestInfrastructure.viewerVideoPlayerPool
                    .acquire(VideoPlaybackRequest("${server.base}/clip.mp4", STREAM_FIXTURE_HEADERS, cache = false), loop = true)
                    .also { it.player.play() }
            }
            val player = lease.player
            try {
                awaitOnMain { player.currentPosition >= 2_000L || player.playerError != null }
                skipIfTheEmulatorDecoderFailed(player)
                onMain {
                    player.seekTo(1_000L)
                    assertTrue(player.currentPosition < 2_000L)
                }
                awaitOnMain(timeoutMs = 5_000L, describe = { "state=${player.playbackState} playing=${player.isPlaying} position=${player.currentPosition}" }) {
                    player.currentPosition >= 2_000L || player.playerError != null
                }
                skipIfTheEmulatorDecoderFailed(player)
                onMain { assertNull(player.playerError) }
            } finally {
                onMain { deviceTestInfrastructure.viewerVideoPlayerPool.recycle(lease) }
            }
        }
    }

    @Test
    fun aHeldInstantSeekInASingleKeyframeClipShowsFramesNearTheTarget_adr_0119() {
        val clip = instrumentation.context.assets.open("single-keyframe-clip.mp4").use { it.readBytes() }
        val surface = ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 4)
        surface.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val rendered = CopyOnWriteArrayList<Long>()
        val frames = VideoFrameMetadataListener { presentationTimeUs, _, _, _ -> rendered += presentationTimeUs / 1_000L }
        StreamFixtureServer(mapOf("/clip.mp4" to FixtureRoute.Body(clip))).use { server ->
            val lease = onMain {
                deviceTestInfrastructure.viewerVideoPlayerPool
                    .acquire(VideoPlaybackRequest("${server.base}/clip.mp4", STREAM_FIXTURE_HEADERS, cache = false), loop = true)
                    .also {
                        it.player.setVideoSurface(surface.surface)
                        it.player.setVideoFrameMetadataListener(frames)
                        it.player.play()
                    }
            }
            val player = lease.player
            try {
                awaitOnMain { player.currentPosition >= 1_000L || player.playerError != null }
                skipIfTheEmulatorDecoderFailed(player)
                onMain { player.pause() }
                Thread.sleep(300L)
                onMain {
                    rendered.clear()
                    player.scrubTo(3_500L)
                }
                awaitOnMain(timeoutMs = 2_000L) { rendered.isNotEmpty() }
                val duringTap = rendered.toList()
                onMain {
                    rendered.clear()
                    player.endScrub()
                    player.seekTo(3_500L)
                }
                Thread.sleep(1_500L)
                val afterTap = rendered.toList()
                assertTrue("held: $duringTap", duringTap.all { it in 3_450L..3_500L })
                assertTrue("released: $afterTap", afterTap.firstOrNull() == 3_500L)
            } finally {
                onMain {
                    player.clearVideoFrameMetadataListener(frames)
                    player.clearVideoSurface()
                    deviceTestInfrastructure.viewerVideoPlayerPool.recycle(lease)
                }
                surface.close()
            }
        }
    }

    private fun skipIfTheEmulatorDecoderFailed(player: Player) = onMain {
        assumeTheEmulatorDecoderWorks((player.playerError?.cause as? MediaCodecDecoderException)?.codecInfo?.name)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun awaitOnMain(timeoutMs: Long = 15_000L, describe: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!onMain(condition)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the player ${onMain(describe)}".trim() }
            Thread.sleep(50L)
        }
    }

    private companion object {
        const val DEVICE_LOG_TAG = "SubtitleTuningDevice"
        const val TIMED_CUES = "WEBVTT\n\n00:00:00.500 --> 00:00:01.000\nEarly cue\n\n00:00:02.000 --> 00:00:02.500\nLate cue\n"
    }
}
