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

/**
 * Asks once, through [ask] ([ViewerScreenHost.onRequestMediaRecovery]), for an episode's stream links to be fetched
 * again when its player fails: they expire. The recovered media replaces the player and starts where it stopped, on
 * the stream with the same label. The ask is spent until the media plays again ([played]), so a recovered stream that
 * fails too shows the failure, and the user picks another stream, rather than asking in a loop. [requests] holds the
 * spent asks by [key] and outlives the player the recovered media replaces.
 */
internal class ViewerStreamRecovery(
    private val requests: MutableSet<String>,
    private val key: String,
    private val ask: () -> Unit,
) {
    /** Asks, unless an ask is spent; whether it asked. */
    fun request(): Boolean = requests.add(key).also { asked -> if (asked) ask() }

    fun played() {
        requests.remove(key)
    }
}
