@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerResolutionState

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream

internal sealed interface ViewerRecoveryDecision {
    data object RetryInPlace : ViewerRecoveryDecision

    data object AskForFreshLinks : ViewerRecoveryDecision

    data class FallBack(val label: String) : ViewerRecoveryDecision

    data object Fail : ViewerRecoveryDecision
}

internal sealed interface ViewerRecoveryEvent {
    data class PlayerError(val errorCode: Int, val positionMs: Long = 0L) : ViewerRecoveryEvent

    data object NothingNew : ViewerRecoveryEvent
}

internal data class ViewerMediaLoad(val media: ViewerMediaKey, val loadGeneration: Long)

internal data class ViewerVideoKey(
    val load: ViewerMediaLoad,
    val label: String?,
    val streams: List<MediaStream>,
    val playback: VideoPlaybackRequest,
) {
    companion object {
        fun of(load: ViewerMediaLoad, media: ImageRef, streamLabel: String?, playback: VideoPlaybackRequest) =
            ViewerVideoKey(load, media.viewerStream(streamLabel)?.label, media.streams, playback)
    }
}

internal class ViewerLoadAttempts {
    var askSpentAtMs: Long? = null

    var played = false

    val failedStreams = mutableSetOf<String>()

    var fallbacks = 0
}

internal class ViewerStreamAttempts {
    private var session: ViewerSessionIdentity? = null
    private val loads = mutableMapOf<ViewerMediaLoad, ViewerLoadAttempts>()

    fun forSession(session: ViewerSessionIdentity?): ViewerStreamAttempts = apply {
        if (session != this.session) loads.clear()
        this.session = session
    }

    fun of(load: ViewerMediaLoad): ViewerLoadAttempts = loads.getOrPut(load) { ViewerLoadAttempts() }
}

internal class ViewerEpisodeStreams(
    val attempts: ViewerStreamAttempts,
    val labels: List<String>,
    val pickedLabel: String?,
    val linksResolvedAtMs: Long?,
    val ask: suspend () -> ViewerMediaRecovery,
    val fallBack: (label: String) -> Unit,
)

internal class ViewerVideoRecovery(
    private val load: ViewerMediaLoad,
    private val episode: ViewerEpisodeStreams? = null,
    private val playingLabel: String? = null,
    loadStartedAtMs: Long = System.currentTimeMillis(),
) {
    private var retriedDecoder = false
    private var openedWithAsk = false
    private val attempts = episode?.attempts?.of(load)
    private val linksOld = episode?.linksResolvedAtMs?.let { loadStartedAtMs - it > VIEWER_LINKS_OLD_AFTER_MS } == true

    fun opening(startMs: Long): ViewerRecoveryDecision? {
        val attempts = attempts ?: return null
        if (load.loadGeneration == 0L || attempts.played || attempts.askSpentAtMs != null) return null
        attempts.askSpentAtMs = startMs
        openedWithAsk = true
        return ViewerRecoveryDecision.AskForFreshLinks
    }

    fun decide(event: ViewerRecoveryEvent): ViewerRecoveryDecision = when (event) {
        is ViewerRecoveryEvent.PlayerError -> playerError(event)
        ViewerRecoveryEvent.NothingNew -> if (openedWithAsk) {
            openedWithAsk = false
            ViewerRecoveryDecision.RetryInPlace
        } else {
            fallBack()
        }
    }

    fun onPlayed() {
        attempts?.played = true
    }

    fun onProgress(positionMs: Long) {
        val attempts = attempts ?: return
        val spentAt = attempts.askSpentAtMs ?: return
        if (positionMs >= spentAt + VIEWER_RECOVERED_PROGRESS_MS) attempts.askSpentAtMs = null
    }

    fun onAskCancelled() {
        attempts?.askSpentAtMs = null
    }

    private fun playerError(error: ViewerRecoveryEvent.PlayerError): ViewerRecoveryDecision {
        val attempts = attempts
        return when {
            error.errorCode in DECODER_ERRORS && !retriedDecoder -> ViewerRecoveryDecision.RetryInPlace.also { retriedDecoder = true }
            attempts == null -> ViewerRecoveryDecision.Fail
            error.errorCode.renewable() && (attempts.played || linksOld) && attempts.askSpentAtMs == null ->
                ViewerRecoveryDecision.AskForFreshLinks.also { attempts.askSpentAtMs = error.positionMs }
            else -> fallBack()
        }
    }

    private fun fallBack(): ViewerRecoveryDecision {
        val episode = episode ?: return ViewerRecoveryDecision.Fail
        val attempts = attempts ?: return ViewerRecoveryDecision.Fail
        playingLabel?.let(attempts.failedStreams::add)
        val playingPicked = playingLabel != null && playingLabel == episode.pickedLabel
        if (playingPicked || attempts.fallbacks >= VIEWER_MAX_STREAM_FALLBACKS) return ViewerRecoveryDecision.Fail
        val next = episode.labels.firstOrNull { it !in attempts.failedStreams } ?: return ViewerRecoveryDecision.Fail
        attempts.fallbacks += 1
        return ViewerRecoveryDecision.FallBack(next)
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

        fun Int.renewable() = this in 2_000..3_999 && this != PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
    }
}

internal fun viewerVideoFailureMessage(errorCode: Int, message: String?): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "This stream uses plain HTTP, which is blocked."
    else -> message ?: VIEWER_VIDEO_FAILED
}

internal const val VIEWER_VIDEO_FAILED = "Could not play video"

internal const val VIEWER_LINKS_OLD_AFTER_MS = 120_000L

fun ViewerResolutionState.linksOld(nowMs: Long = System.currentTimeMillis()): Boolean =
    resolvedAtMs?.let { nowMs - it > VIEWER_LINKS_OLD_AFTER_MS } != false

internal const val VIEWER_RECOVERED_PROGRESS_MS = 30_000L

internal const val VIEWER_MAX_STREAM_FALLBACKS = 3
