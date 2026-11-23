@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import androidx.media3.common.PlaybackException
import com.theoriacodex.app.viewer.state.ViewerMediaKey

/** What a failed Viewer video does next ([ViewerVideoRecovery.decide]). */
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
    /** The player failed with [errorCode], a [PlaybackException] code. */
    data class PlayerError(val errorCode: Int) : ViewerRecoveryEvent

    /** Fresh links were asked for and nothing new came ([ViewerMediaRecovery.NOTHING_NEW]). */
    data object NothingNew : ViewerRecoveryEvent
}

/** One load of [media]: Retry starts the next ([loadGeneration] + 1). */
internal data class ViewerMediaLoad(val media: ViewerMediaKey, val loadGeneration: Long)

/**
 * What one episode load has tried so far. It outlives the players a load goes through (fresh links, a fallback
 * stream), so a recovered stream that fails too moves on rather than asking in a loop.
 */
internal class ViewerLoadAttempts {
    /** An ask for fresh links was spent; a rendered frame gives it back. */
    var asked = false
    /** A frame of this load rendered. */
    var played = false
    /** A manual Retry opened this load with an ask, whose nothing-new replays in place once. */
    var retryOpened = false
    var retryAskPending = false
    val failedStreams = mutableSetOf<String>()
    var fallbacks = 0
}

/** Each episode load's [ViewerLoadAttempts], for the whole Viewer screen. */
internal class ViewerStreamAttempts {
    private val loads = mutableMapOf<ViewerMediaLoad, ViewerLoadAttempts>()

    fun of(load: ViewerMediaLoad): ViewerLoadAttempts = loads.getOrPut(load) { ViewerLoadAttempts() }
}

/**
 * An episode's streams as its recovery sees them: [labels] in the source's order, whether the stream playing was
 * [pickedByHand] in this episode, when its links were looked up ([linksResolvedAtMs]), how to [ask] the route for fresh
 * links (it returns once the lookup ends, within its limit) and how to [fallBack] to another stream for this load.
 */
internal class ViewerEpisodeStreams(
    val attempts: ViewerStreamAttempts,
    val labels: List<String>,
    val pickedByHand: Boolean,
    val linksResolvedAtMs: Long?,
    val ask: suspend () -> ViewerMediaRecovery,
    val fallBack: (label: String) -> Unit,
)

/**
 * How one video [load] recovers from player errors; [episode] is null for media without streams.
 *
 * - A decoder failure retries in place, once.
 * - An episode stream that had played in this load, or whose links are old (looked up more than
 *   [VIEWER_LINKS_OLD_AFTER_MS] before the load began), asks for fresh links once on an I/O or parsing error: an
 *   expired link answers with an error status, or with an error page that reads as a malformed manifest. A rendered
 *   frame gives the ask back.
 * - Otherwise (a stream that never played on fresh links: a dead server), or when fresh links brought nothing new, the
 *   episode falls back to its next stream not tried yet, at most [VIEWER_MAX_STREAM_FALLBACKS] times a load, unless
 *   the user picked the stream playing by hand; then it fails, with the picker.
 * - A manual Retry ([ViewerMediaLoad.loadGeneration] above 0) of an episode opens with one ask ([opensWithAsk]) instead
 *   of replaying the dead address; when it brings nothing new, the address replays in place once.
 * - Media without streams fails on anything but its one decoder retry.
 */
internal class ViewerVideoRecovery(
    private val load: ViewerMediaLoad,
    private val episode: ViewerEpisodeStreams? = null,
    private val playingLabel: String? = null,
    loadStartedAtMs: Long = System.currentTimeMillis(),
) {
    private var retriedDecoder = false
    private val attempts = episode?.attempts?.of(load)
    private val linksOld = episode?.linksResolvedAtMs?.let { loadStartedAtMs - it > VIEWER_LINKS_OLD_AFTER_MS } == true

    /** Whether this player asks for fresh links before it plays: the first one of a manual Retry of an episode. */
    fun opensWithAsk(): Boolean {
        val attempts = attempts ?: return false
        if (load.loadGeneration == 0L || attempts.retryOpened) return false
        attempts.retryOpened = true
        attempts.retryAskPending = true
        attempts.asked = true
        return true
    }

    fun decide(event: ViewerRecoveryEvent): ViewerRecoveryDecision = when (event) {
        is ViewerRecoveryEvent.PlayerError -> playerError(event.errorCode)
        ViewerRecoveryEvent.NothingNew -> if (attempts?.retryAskPending == true) {
            attempts.retryAskPending = false
            ViewerRecoveryDecision.RetryInPlace
        } else {
            fallBack()
        }
    }

    fun onPlayed() {
        val attempts = attempts ?: return
        attempts.played = true
        attempts.asked = false
        attempts.retryAskPending = false
    }

    private fun playerError(errorCode: Int): ViewerRecoveryDecision {
        val attempts = attempts
        return when {
            errorCode in DECODER_ERRORS && !retriedDecoder -> ViewerRecoveryDecision.RetryInPlace.also { retriedDecoder = true }
            attempts == null -> ViewerRecoveryDecision.Fail
            (attempts.played || linksOld) && errorCode.isIoOrParsing() && !attempts.asked ->
                ViewerRecoveryDecision.AskForFreshLinks.also { attempts.asked = true }
            else -> fallBack()
        }
    }

    private fun fallBack(): ViewerRecoveryDecision {
        val episode = episode ?: return ViewerRecoveryDecision.Fail
        val attempts = attempts ?: return ViewerRecoveryDecision.Fail
        playingLabel?.let(attempts.failedStreams::add)
        if (episode.pickedByHand || attempts.fallbacks >= VIEWER_MAX_STREAM_FALLBACKS) return ViewerRecoveryDecision.Fail
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

        /** [PlaybackException] numbers its I/O errors 2000–2999 and its parsing errors 3000–3999. */
        fun Int.isIoOrParsing() = this in 2_000..3_999
    }
}

/** How long after its lookup an episode's links count as old enough to have expired. */
internal const val VIEWER_LINKS_OLD_AFTER_MS = 120_000L

/** How many other streams one episode load falls back to before it shows the failure. */
internal const val VIEWER_MAX_STREAM_FALLBACKS = 3
