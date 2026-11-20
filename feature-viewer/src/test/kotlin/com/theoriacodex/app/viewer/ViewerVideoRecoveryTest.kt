package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerVideoRecoveryTest {
    @Test
    fun `decoder and frame rendering failures receive one recovery attempt`() {
        val errors = listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
        errors.forEach { error ->
            val recovery = ViewerVideoRecovery()
            assertTrue("First recovery for $error", recovery.requestRetry(error))
            assertFalse("Repeated failure for $error", recovery.requestRetry(error))
            assertFalse("Different renderer failure after recovery", recovery.requestRetry(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            ))
        }
    }

    @Test
    fun `unsupported media and source errors keep the existing error path`() {
        val errors = listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )
        errors.forEach { error ->
            val recovery = ViewerVideoRecovery()
            assertFalse("No renderer retry for $error", recovery.requestRetry(error))
            assertTrue(recovery.requestRetry(PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
    }

    @Test
    fun `a new media load receives its own retry budget`() {
        val previous = ViewerVideoRecovery()
        assertTrue(previous.requestRetry(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(previous.requestRetry(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(ViewerVideoRecovery().requestRetry(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }
}
