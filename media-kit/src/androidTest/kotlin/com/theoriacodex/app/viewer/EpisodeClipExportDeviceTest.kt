package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.media3.transformer.ExportException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.MediaTrack
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeClipExportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aClipOfAnEncryptedHlsEpisodeKeepsOnlyItsRangeAndBurnsInTheCueShownThen_adr_0223() {
        val routes = streamFixtureRoutes(instrumentation.context.assets) +
            ("/subtitles/late.vtt" to FixtureRoute.Body(LATE_CUE.toByteArray()))
        StreamFixtureServer(routes).use { server ->
            val stream = MediaStream(
                url = "${server.base}/episode",
                label = "Fixture",
                mime = "application/vnd.apple.mpegurl",
                headers = STREAM_FIXTURE_HEADERS,
                subtitles = listOf(MediaTrack("${server.base}/subtitles/late.vtt", "English")),
                audioTracks = listOf(MediaTrack("${server.base}/hls/audio.m3u8", "Japanese")),
            )
            val output = File(context.cacheDir, "clip-device-test.mp4").apply { delete() }
            val progress = CopyOnWriteArrayList<Int>()
            val result = skippingTheEmulatorDecoder {
                exportEpisodeClip(context, VideoPlaybackInfrastructure(context), stream, EpisodeClipRange(1_000L, 4_000L),
                    EpisodeClipTracks(subtitles = true, subtitleIndex = 0, audioIndex = 0), output) { progress += it }
            }

            assertTrue(result.subtitlesBurned)
            assertTrue(server.requests.toString(), server.requests.all { it.authorised })
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(output.path)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("duration $durationMs", durationMs in 2_800L..3_200L)
                assertEquals("540", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val beforeCue = retriever.frameAt(500L).subtitleInk()
                val duringCue = retriever.frameAt(1_500L).subtitleInk()
                assertTrue("before $beforeCue, during $duringCue", duringCue > beforeCue + MIN_CUE_INK)
            }
            output.delete()
        }
    }

    private fun <T> skippingTheEmulatorDecoder(export: suspend () -> T): T = try {
        runBlocking { export() }
    } catch (failure: EpisodeClipException) {
        assumeTheEmulatorDecoderWorks((failure.cause as? ExportException)?.codecInfo?.name)
        throw failure
    }

    private fun MediaMetadataRetriever.frameAt(ms: Long): Bitmap =
        requireNotNull(getFrameAtTime(ms * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST))

    private fun Bitmap.subtitleInk(): Int {
        var count = 0
        for (y in height * 2 / 3 until height) for (x in 0 until width) {
            val pixel = getPixel(x, y)
            if (Color.red(pixel) > WHITE && Color.green(pixel) > WHITE && Color.blue(pixel) > WHITE) count++
        }
        return count
    }

    private companion object {
        const val LATE_CUE = "WEBVTT\n\n00:00:02.000 --> 00:00:03.000\nClip cue\n"
        const val WHITE = 225
        const val MIN_CUE_INK = 200
    }
}
