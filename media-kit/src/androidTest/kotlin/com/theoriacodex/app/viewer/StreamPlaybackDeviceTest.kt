package com.theoriacodex.app.viewer

import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.MediaTrack
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays the stream fixture on real decoders: an HLS episode that needs a header, whose video carries no audio, with
 * separate audio and a WebVTT file, served from a loopback server in this process.
 */
@RunWith(AndroidJUnit4::class)
class StreamPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun anHlsEpisodePlaysWithItsSeparateAudioAndSubtitles() {
        StreamFixtureServer(streamFixtureRoutes(instrumentation.context.assets)).use { server ->
            val cues = CopyOnWriteArrayList<String>()
            val lease = onMain {
                val pool = VideoPlaybackInfrastructure(instrumentation.targetContext).viewerVideoPlayerPool
                pool.acquire(
                    VideoPlaybackRequest(
                        location = "${server.base}/episode",
                        headers = STREAM_FIXTURE_HEADERS,
                        mime = "application/vnd.apple.mpegurl",
                        subtitles = listOf(MediaTrack("${server.base}/subtitles/en.vtt", "English")),
                        audioTracks = listOf(MediaTrack("${server.base}/hls/audio.m3u8", "Japanese")),
                        cache = false,
                    ),
                    loop = false,
                ).also { lease ->
                    lease.player.addListener(object : Player.Listener {
                        override fun onCues(cueGroup: CueGroup) {
                            cueGroup.cues.mapNotNullTo(cues) { it.text?.toString() }
                        }
                    })
                }
            }
            val player = lease.player
            awaitOnMain { player.playbackState == Player.STATE_READY || player.playerError != null }
            onMain {
                player.selectSubtitle(player.playbackTracks().subtitles.single { it.label == "English" }.id)
                player.play()
            }
            awaitOnMain { player.currentPosition >= 1_500L || player.playerError != null }
            awaitOnMain { "Fixture cue" in cues || player.playerError != null }

            onMain {
                assertNull(player.playerError)
                assertTrue(player.currentPosition >= 1_500L)
                assertEquals(listOf("Japanese"), player.playbackTracks().audio.filter { it.selected }.map { it.label })
                assertTrue(server.requests.all { it.authorised })
                player.release()
            }
            assertTrue(cues.toString(), "Fixture cue" in cues)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun awaitOnMain(timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!onMain(condition)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the player" }
            Thread.sleep(50L)
        }
    }
}
