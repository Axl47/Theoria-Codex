package com.theoriacodex.app.ui.undo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Names the surface that draws an offer: a screen or sheet ([scope]), or one row or tile cell in it ([key]).
 * A surface draws an offer only when the offer's anchor equals its own.
 */
data class UndoAnchor(val scope: String, val key: String? = null) {
    companion object {
        /** The app shell's own pill over every screen, for actions whose surface draws no offer, such as the Viewer. */
        val App = UndoAnchor("app")
    }
}

/** What the offer's button says: Undo after a change, Retry after a failure. */
enum class UndoAction(val label: String) { Undo("Undo"), Retry("Retry") }

/** How long an offer stays before it expires. */
sealed interface UndoLifetime {
    data class Timed(val duration: Duration = DEFAULT_UNDO_DURATION) : UndoLifetime

    /** Until the anchor's surface is left: [UndoSlot.expire] through [UndoLeaveEffect], or [DeferredRemovals.commitAll]. */
    data object UntilLeft : UndoLifetime
}

val DEFAULT_UNDO_DURATION = 5.seconds

/** Stretches timed lifetimes to what the accessibility services recommend; [ProvideUndoSlot] sets [stretch]. */
object UndoTimeouts {
    @Volatile
    var stretch: (Duration) -> Duration = { it }
}

/**
 * One small in-memory reversal (or a Retry) the user can take where it happened. [act] runs when the button is
 * pressed; [onExpire] runs when the offer times out, is replaced, or its surface is left. Both run in the scope the
 * owner passed to [UndoSlot.offer], never in composition.
 *
 * A newer offer replaces this one at any time, so [onExpire] must not do real work. Deletions that wait for their
 * Undo to pass belong in [DeferredRemovals], which keeps each one until its own lifetime ends.
 */
class UndoOffer(
    val label: String,
    val anchor: UndoAnchor,
    val action: UndoAction = UndoAction.Undo,
    val lifetime: UndoLifetime = UndoLifetime.Timed(),
    val onExpire: suspend () -> Unit = {},
    val act: suspend () -> Unit,
)

/**
 * Holds at most one [UndoOffer] for the whole app (one per Activity, read through [LocalUndoSlot]). Nothing
 * queues: a newer offer expires the one it replaces. Owners keep their own restore logic and emit here;
 * surfaces draw the offer anchored to them with [UndoPill] or [UndoRow].
 *
 * [scope] is for owners without a longer-lived scope of their own (composables): the app passes its
 * Activity-retained owner's, so an offer survives a configuration change.
 */
class UndoSlot(val scope: CoroutineScope = CoroutineScope(SupervisorJob())) {
    private class Live(val offer: UndoOffer, val scope: CoroutineScope) {
        lateinit var timer: Job
    }

    private val lock = Any()
    private var live: Live? = null
    private val current = MutableStateFlow<UndoOffer?>(null)

    /** The live offer, or null. */
    val offer: StateFlow<UndoOffer?> = current.asStateFlow()

    /** Stretches a timed lifetime, so accessibility services get their recommended time; see [UndoTimeouts]. */
    var timeout: (Duration) -> Duration = { UndoTimeouts.stretch(it) }

    /**
     * Shows [offer], first expiring any live one. Its timer, [UndoOffer.act] and [UndoOffer.onExpire] run in
     * [scope]; if [scope] is cancelled while the offer is live, the offer disappears and neither runs. An offer from
     * a scope already cancelled is dropped and leaves the live offer alone.
     */
    fun offer(scope: CoroutineScope, offer: UndoOffer) {
        if (!scope.isActive) return
        val entry = Live(offer, scope)
        entry.timer = scope.launch(start = CoroutineStart.LAZY) {
            when (val lifetime = offer.lifetime) {
                is UndoLifetime.Timed -> delay(timeout(lifetime.duration))
                UndoLifetime.UntilLeft -> awaitCancellation()
            }
            if (take(entry)) offer.onExpire()
        }
        val replaced = synchronized(lock) {
            live.also {
                live = entry
                current.value = offer
            }
        }
        replaced?.let(::expireReplaced)
        entry.timer.invokeOnCompletion { take(entry) }
        entry.timer.start()
    }

    /** Runs [offer]'s action if it is still the live offer, and clears it without expiring. */
    fun act(offer: UndoOffer) {
        val entry = takeLive { it.offer === offer } ?: return
        entry.timer.cancel()
        entry.scope.launch { offer.act() }
    }

    /** Expires the live offer if it is anchored anywhere in [anchorScope]; called when that surface is left. */
    fun expire(anchorScope: String) {
        takeLive { it.offer.anchor.scope == anchorScope }?.let(::expireReplaced)
    }

    /**
     * Shows an offer and suspends until the user acts (true) or it expires, is replaced or its surface is
     * left (false). For owners whose restore is a suspending workflow: the offer lives in the caller's scope.
     */
    suspend fun awaitAction(
        label: String,
        anchor: UndoAnchor,
        action: UndoAction = UndoAction.Undo,
        lifetime: UndoLifetime = UndoLifetime.Timed(),
    ): Boolean = coroutineScope {
        val outcome = CompletableDeferred<Boolean>()
        offer(this, UndoOffer(label, anchor, action, lifetime, onExpire = { outcome.complete(false) }) { outcome.complete(true) })
        outcome.await()
    }

    private fun expireReplaced(entry: Live) {
        entry.timer.cancel()
        entry.scope.launch { entry.offer.onExpire() }
    }

    /** Clears [entry] if it is still live; true when this call cleared it. */
    private fun take(entry: Live): Boolean = takeLive { it === entry } != null

    private fun takeLive(matches: (Live) -> Boolean): Live? = synchronized(lock) {
        live?.takeIf(matches)?.also {
            live = null
            current.value = null
        }
    }
}

fun UndoOffer?.shownAt(anchor: UndoAnchor): UndoOffer? = this?.takeIf { it.anchor == anchor }
