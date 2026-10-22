@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException

/** One automatic recovery per media load; successful frames do not replenish the budget. */
internal class ViewerVideoRecovery {
    private var attempted = false

    fun requestRetry(errorCode: Int): Boolean {
        val recoverable = when (errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> true
            else -> false
        }
        if (attempted || !recoverable) return false
        attempted = true
        return true
    }
}
