package com.theoriacodex.app.viewer

import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.MediaTrack
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
                infrastructure.viewerVideoPlayerPool.acquire(
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
                    infrastructure.viewerVideoPlayerPool.recycle(lease)
                }
            }
        }
    }

    @Test
    fun aLoopingVideoKeepsPlayingAfterASeek() {
        val clip = instrumentation.context.assets.open("looping-clip.mp4").use { it.readBytes() }
        StreamFixtureServer(mapOf("/clip.mp4" to FixtureRoute.Body(clip))).use { server ->
            val lease = onMain {
                infrastructure.viewerVideoPlayerPool
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
                onMain { infrastructure.viewerVideoPlayerPool.recycle(lease) }
            }
        }
    }

    private fun skipIfTheEmulatorDecoderFailed(player: Player) = onMain {
        val decoder = (player.playerError?.cause as? MediaCodecDecoderException)?.codecInfo?.name
        assumeFalse("This emulator's $decoder cannot decode", decoder?.startsWith("c2.goldfish") == true)
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
        val infrastructure by lazy { VideoPlaybackInfrastructure(InstrumentationRegistry.getInstrumentation().targetContext) }
    }
}
