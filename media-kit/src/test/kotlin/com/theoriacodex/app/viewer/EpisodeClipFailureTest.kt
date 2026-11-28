package com.theoriacodex.app.viewer

import androidx.media3.transformer.ExportException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpisodeClipFailureTest {
    @Test
    fun `a failed export keeps Media3's error behind the message the user sees (ADR 0223)`() {
        val decoder = ExportException.createForCodec(IllegalStateException("queue -- bad input"),
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.CodecInfo("video/avc", true, true, "c2.goldfish.h264.decoder"))
        val network = ExportException.createForAssetLoader(IOException(), ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        assertEquals(
            listOf("Could not save this clip. Try again.", "The episode stopped loading. Clip it again from the episode."),
            listOf(decoder, network).map { it.asEpisodeClipFailure().message },
        )
        assertSame(decoder, decoder.asEpisodeClipFailure().cause)
    }
}
