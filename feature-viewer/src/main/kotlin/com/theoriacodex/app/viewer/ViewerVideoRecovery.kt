@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream

/**
 * What a failed Viewer video does next ([ViewerVideoRecovery.decide]). The player holds the one in progress as its
 * recovery state; null there means it plays normally.
 */
internal sealed interface ViewerRecoveryDecision {
    /** A new player for the same request: a decoder that failed may work the second time. */
    data object RetryInPlace : ViewerRecoveryDecision

    /** The episode's links are fetched again ([ViewerEpisodeStreams.ask]); the recovered media replaces the player. */
    data object AskForFreshLinks : ViewerRecoveryDecision

    /** The episode plays its stream [label] for the rest of this load ([ViewerEpisodeStreams.fallBack]). */
    data class FallBack(val label: String) : ViewerRecoveryDecision

    /** The failure shows, with Retry, and for an episode its stream picker. */
    data object Fail : ViewerRecoveryDecision
}

/** What [ViewerVideoRecovery.decide] decides on. */
internal sealed interface ViewerRecoveryEvent {
    /** The player failed with [errorCode], a [PlaybackException] code, at [positionMs]. */
    data class PlayerError(val errorCode: Int, val positionMs: Long = 0L) : ViewerRecoveryEvent

    /** Fresh links were asked for and nothing new came ([ViewerMediaRecovery.NOTHING_NEW]). */
    data object NothingNew : ViewerRecoveryEvent
}

/** One load of [media]: Retry starts the next ([loadGeneration] + 1). */
internal data class ViewerMediaLoad(val media: ViewerMediaKey, val loadGeneration: Long)

/**
 * What a Viewer player is, for its state: a change of any part is a new player with a fresh recovery. The playing
 * stream's [label] is part of it because two of an episode's streams can share one request (two servers offering the
 * same address), and moving between them must still replace the failed player.
 */
internal data class ViewerVideoKey(
    val load: ViewerMediaLoad,
    val label: String?,
    val streams: List<MediaStream>,
    val playback: VideoPlaybackRequest,
) {
    companion object {
        /** The key of [media]'s [playback] in [load], playing its stream [streamLabel] (else its first). */
        fun of(load: ViewerMediaLoad, media: ImageRef, streamLabel: String?, playback: VideoPlaybackRequest) =
            ViewerVideoKey(load, media.viewerStream(streamLabel)?.label, media.streams, playback)
    }
}

/**
 * What one episode load has tried so far. It outlives the players a load goes through (fresh links, a fallback
 * stream), so a recovered stream that fails too moves on rather than asking in a loop.
 */
internal class ViewerLoadAttempts {
    /** Where the failure that spent this load's ask for fresh links happened; null while the ask is free. */
    var askSpentAtMs: Long? = null

    /** A frame of this load rendered. */
    var played = false

    /** The streams that failed in this load; all but the first were fallbacks. */
    val failedStreams = mutableSetOf<String>()
}

/** Each episode load's [ViewerLoadAttempts] in one Viewer session; another session starts with none ([forSession]). */
internal class ViewerStreamAttempts {
    private var session: ViewerSessionIdentity? = null
    private val loads = mutableMapOf<ViewerMediaLoad, ViewerLoadAttempts>()

    /** These attempts for [session]: a session replacing the last one, whose loads start over at 0, inherits nothing. */
    fun forSession(session: ViewerSessionIdentity?): ViewerStreamAttempts = apply {
        if (session != this.session) loads.clear()
        this.session = session
    }

    fun of(load: ViewerMediaLoad): ViewerLoadAttempts = loads.getOrPut(load) { ViewerLoadAttempts() }
}

/**
 * An episode's streams as its recovery sees them: [labels] in the source's order, the one the user picked by hand in
 * this episode ([pickedLabel]; the stream playing may differ, when fresh links no longer offer it), when its links were looked up ([linksResolvedAtMs]), how to [ask] the route for fresh
 * links (it returns once the lookup ends, within its limit) and how to [fallBack] to another stream for this load.
 */
internal class ViewerEpisodeStreams(
    val attempts: ViewerStreamAttempts,
    val labels: List<String>,
    val pickedLabel: String?,
    val linksResolvedAtMs: Long?,
    val ask: suspend () -> ViewerMediaRecovery,
    val fallBack: (label: String) -> Unit,
)

/**
 * How one player of a video [load] recovers from player errors; [episode] is null for media without streams.
 *
 * - A decoder failure retries in place, once.
 * - An episode stream that had played in this load, or whose links are old (looked up more than
 *   [VIEWER_LINKS_OLD_AFTER_MS] before the load began), asks for fresh links once on an I/O or parsing error: an
 *   expired link answers with an error status, or with an error page that reads as a malformed manifest. The ask comes
 *   back once playback gets [VIEWER_RECOVERED_PROGRESS_MS] past the failure that spent it ([onProgress]), or when its
 *   lookup is cancelled ([onAskCancelled]). Plain HTTP, which the app refuses, never asks: new links cannot fix it.
 * - Otherwise (a stream that never played on fresh links: a dead server), or when fresh links brought nothing new, the
 *   episode falls back to its next stream not tried yet, at most [VIEWER_MAX_STREAM_FALLBACKS] times a load, unless
 *   the stream playing is the one the user picked by hand; then it fails, with the picker.
 * - A manual Retry ([ViewerMediaLoad.loadGeneration] above 0) of an episode opens with the ask ([opening]) instead of
 *   replaying the dead address; when it brings nothing new, the address replays in place once.
 * - Media without streams fails on anything but its one decoder retry.
 */
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

    /**
     * What this player does before it plays, starting at [startMs]: a Retry of an episode that has not played asks for
     * fresh links first; anything else plays at once (null).
     */
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

    /** A frame of this load rendered. */
    fun onPlayed() {
        attempts?.played = true
    }

    /** Playback is at [positionMs]: far enough past the failure that spent the ask, the ask comes back. */
    fun onProgress(positionMs: Long) {
        val attempts = attempts ?: return
        val spentAt = attempts.askSpentAtMs ?: return
        if (positionMs >= spentAt + VIEWER_RECOVERED_PROGRESS_MS) attempts.askSpentAtMs = null
    }

    /** The lookup ended without an outcome (the page left, or another stream was picked): the ask was not used. */
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
        if (playingPicked || attempts.failedStreams.size > VIEWER_MAX_STREAM_FALLBACKS) return ViewerRecoveryDecision.Fail
        val next = episode.labels.firstOrNull { it !in attempts.failedStreams } ?: return ViewerRecoveryDecision.Fail
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

        /**
         * Whether fresh links may fix this error: [PlaybackException] numbers its I/O errors 2000–2999 and its parsing
         * errors 3000–3999. Plain HTTP is refused by the app itself, whatever the links.
         */
        fun Int.renewable() = this in 2_000..3_999 && this != PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
    }
}

/** What a video that ends in [errorCode] tells the user: why, when it is the app's own refusal; else [message]. */
internal fun viewerVideoFailureMessage(errorCode: Int, message: String?): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "This stream uses plain HTTP, which is blocked."
    else -> message ?: VIEWER_VIDEO_FAILED
}

internal const val VIEWER_VIDEO_FAILED = "Could not play video"

/** How long after its lookup an episode's links count as old enough to have expired. */
internal const val VIEWER_LINKS_OLD_AFTER_MS = 120_000L

/**
 * How far past the failure that spent it playback must get before the ask for fresh links comes back. A rendered frame
 * is not enough: every lookup returns newly signed links, so a stream that dies at a fixed point (a missing segment, a
 * host that cuts the file at an offset) would resume just before it, render, fail and ask again forever. Thirty
 * seconds is past any one segment, and far shorter than links that expire every few minutes.
 */
internal const val VIEWER_RECOVERED_PROGRESS_MS = 30_000L

/** How many other streams one episode load falls back to before it shows the failure. */
internal const val VIEWER_MAX_STREAM_FALLBACKS = 3
