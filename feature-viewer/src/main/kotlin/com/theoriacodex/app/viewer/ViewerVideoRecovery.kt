@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.state.ViewerMediaKey

/** What a failed Viewer video does next ([ViewerVideoRecovery.decide]). */
internal enum class ViewerRecoveryDecision {
    /** A new player for the same request: a decoder that failed may work the second time. */
    RETRY_IN_PLACE,

    /** The episode's links are fetched again ([ViewerFreshLinks.ask]); the recovered media replaces the player. */
    ASK_FOR_FRESH_LINKS,

    /** The failure shows, with Retry, and for an episode its stream picker. */
    FAIL,
}

/** One load of [media]: Retry starts the next ([loadGeneration] + 1), which earns a fresh ask. */
internal data class ViewerMediaLoad(val media: ViewerMediaKey, val loadGeneration: Long)

/**
 * The asks for fresh links a Viewer screen has spent, one per [ViewerMediaLoad]. It outlives the players that recovered
 * media replaces, so a recovered stream that fails too shows the failure rather than asking in a loop.
 */
internal class ViewerFreshLinksBudget {
    private val spent = mutableSetOf<ViewerMediaLoad>()

    /** Spends [load]'s ask; false when it was already spent. */
    fun spend(load: ViewerMediaLoad): Boolean = spent.add(load)

    fun restore(load: ViewerMediaLoad) {
        spent.remove(load)
    }
}

/**
 * How media whose links expire (an episode's streams) has them fetched again: [ask]
 * ([ViewerScreenHost.onRequestMediaRecovery]), at most once per load from the screen's [budget].
 */
internal class ViewerFreshLinks(val budget: ViewerFreshLinksBudget, val ask: () -> Unit)

/**
 * How one video [load] recovers from player errors. A decoder failure retries in place, once. An I/O error (an HTTP
 * status, the network, a missing file: what an expired link produces) asks for fresh links when the media has them
 * ([budget] not null), once per load; the ask comes back once the media plays ([onPlayed]). Anything else, a malformed
 * manifest for one, fails at once.
 */
internal class ViewerVideoRecovery(
    private val load: ViewerMediaLoad,
    private val budget: ViewerFreshLinksBudget? = null,
) {
    private var retried = false

    fun decide(errorCode: Int): ViewerRecoveryDecision = when {
        errorCode in DECODER_ERRORS && !retried -> ViewerRecoveryDecision.RETRY_IN_PLACE.also { retried = true }
        errorCode in EXPIRED_LINK_ERRORS && budget?.spend(load) == true -> ViewerRecoveryDecision.ASK_FOR_FRESH_LINKS
        else -> ViewerRecoveryDecision.FAIL
    }

    fun onPlayed() {
        budget?.restore(load)
    }

    private companion object {
        val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )

        /** An expired link answers with an error status, an error page instead of video, or not at all. */
        val EXPIRED_LINK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }
}
