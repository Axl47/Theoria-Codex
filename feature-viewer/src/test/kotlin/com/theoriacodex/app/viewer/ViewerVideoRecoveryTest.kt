package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.ASK_FOR_FRESH_LINKS
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.FAIL
import com.theoriacodex.app.viewer.ViewerRecoveryDecision.RETRY_IN_PLACE
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerVideoRecoveryTest {
    private val episode = ViewerMediaKey(PostId(SourceKey.HITOMI, "episode"), 0)
    private val expired = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test
    fun `a decoder failure retries in place once per load`() {
        val decoderErrors = listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
        decoderErrors.forEach { error ->
            val recovery = ViewerVideoRecovery(ViewerMediaLoad(episode, 0))
            assertEquals("First failure for $error", RETRY_IN_PLACE, recovery.decide(error))
            assertEquals("Repeated failure for $error", FAIL, recovery.decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        }
    }

    @Test
    fun `an expired link asks once for fresh links, only on media that has them`() {
        val budget = ViewerFreshLinksBudget()
        val load = ViewerMediaLoad(episode, 0)
        assertEquals(FAIL, ViewerVideoRecovery(load).decide(expired))

        assertEquals(ASK_FOR_FRESH_LINKS, ViewerVideoRecovery(load, budget).decide(expired))
        // The player the recovered media brings fails too: the ask is spent until the media plays.
        val recovered = ViewerVideoRecovery(load, budget)
        assertEquals(FAIL, recovered.decide(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        recovered.onPlayed()
        assertEquals(ASK_FOR_FRESH_LINKS, ViewerVideoRecovery(load, budget).decide(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun `an explicit retry earns one more ask`() {
        val budget = ViewerFreshLinksBudget()
        assertEquals(ASK_FOR_FRESH_LINKS, ViewerVideoRecovery(ViewerMediaLoad(episode, 0), budget).decide(expired))
        assertEquals(FAIL, ViewerVideoRecovery(ViewerMediaLoad(episode, 0), budget).decide(expired))
        assertEquals(ASK_FOR_FRESH_LINKS, ViewerVideoRecovery(ViewerMediaLoad(episode, 1), budget).decide(expired))
    }

    @Test
    fun `anything but an expired link fails at once`() {
        val errors = listOf(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )
        errors.forEach { error ->
            assertEquals("Error $error", FAIL, ViewerVideoRecovery(ViewerMediaLoad(episode, 0), ViewerFreshLinksBudget()).decide(error))
        }
    }
}
