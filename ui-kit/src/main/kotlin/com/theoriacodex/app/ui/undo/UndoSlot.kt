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

data class UndoAnchor(val scope: String, val key: String? = null) {
    companion object {
        val App = UndoAnchor("app")
    }
}

enum class UndoAction(val label: String) { Undo("Undo"), Retry("Retry") }

sealed interface UndoLifetime {
    data class Timed(val duration: Duration = DEFAULT_UNDO_DURATION) : UndoLifetime

    data object UntilLeft : UndoLifetime
}

val DEFAULT_UNDO_DURATION = 5.seconds

object UndoTimeouts {
    @Volatile
    var stretch: (Duration) -> Duration = { it }
}

class UndoOffer(
    val label: String,
    val anchor: UndoAnchor,
    val action: UndoAction = UndoAction.Undo,
    val lifetime: UndoLifetime = UndoLifetime.Timed(),
    val onExpire: suspend () -> Unit = {},
    val act: suspend () -> Unit,
)

class UndoSlot(val scope: CoroutineScope = CoroutineScope(SupervisorJob())) {
    private class Live(val offer: UndoOffer, val scope: CoroutineScope) {
        lateinit var timer: Job
    }

    private val lock = Any()
    private var live: Live? = null
    private val current = MutableStateFlow<UndoOffer?>(null)

    val offer: StateFlow<UndoOffer?> = current.asStateFlow()

    var timeout: (Duration) -> Duration = { UndoTimeouts.stretch(it) }

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

    fun act(offer: UndoOffer) {
        val entry = takeLive { it.offer === offer } ?: return
        entry.timer.cancel()
        entry.scope.launch { offer.act() }
    }

    fun expire(anchorScope: String) {
        takeLive { it.offer.anchor.scope == anchorScope }?.let(::expireReplaced)
    }

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

    private fun take(entry: Live): Boolean = takeLive { it === entry } != null

    private fun takeLive(matches: (Live) -> Boolean): Live? = synchronized(lock) {
        live?.takeIf(matches)?.also {
            live = null
            current.value = null
        }
    }
}

fun UndoOffer?.shownAt(anchor: UndoAnchor): UndoOffer? = this?.takeIf { it.anchor == anchor }
