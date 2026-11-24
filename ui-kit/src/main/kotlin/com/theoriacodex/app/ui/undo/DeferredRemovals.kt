package com.theoriacodex.app.ui.undo

import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

data class PendingRemoval(val label: String, val status: Status = Status.Waiting) {
    enum class Status {
        Waiting,

        Committing,

        Failed,
    }
}

class DeferredRemovals<K : Any>(
    private val scope: CoroutineScope,
    private val commit: suspend (K) -> Boolean,
) {
    private val entries = MutableStateFlow<Map<K, PendingRemoval>>(emptyMap())
    private val timers = mutableMapOf<K, Job>()
    private val lock = Any()

    val pending: StateFlow<Map<K, PendingRemoval>> = entries.asStateFlow()

    fun remove(key: K, label: String, lifetime: UndoLifetime = UndoLifetime.Timed()) {
        var accepted = false
        entries.update { current ->
            accepted = current[key]?.status != PendingRemoval.Status.Committing
            if (accepted) current + (key to PendingRemoval(label)) else current
        }
        if (!accepted) return
        when (lifetime) {
            is UndoLifetime.Timed -> schedule(key, lifetime.duration) { commitNow(key) }
            UndoLifetime.UntilLeft -> cancelTimer(key)
        }
    }

    fun undo(key: K) {
        var removed = false
        entries.update { current ->
            removed = current[key]?.let { it.status != PendingRemoval.Status.Committing } == true
            if (removed) current - key else current
        }
        if (removed) cancelTimer(key)
    }

    fun retry(key: K) {
        if (entries.value[key]?.status == PendingRemoval.Status.Failed) commitNow(key)
    }

    fun act(key: K) {
        if (entries.value[key]?.status == PendingRemoval.Status.Failed) retry(key) else undo(key)
    }

    fun commitAll() {
        entries.value.forEach { (key, removal) ->
            when (removal.status) {
                PendingRemoval.Status.Waiting -> commitNow(key)
                PendingRemoval.Status.Failed -> undo(key)
                PendingRemoval.Status.Committing -> Unit
            }
        }
    }

    private fun commitNow(key: K) {
        var started = false
        entries.update { current ->
            val entry = current[key]?.takeIf { it.status != PendingRemoval.Status.Committing }
            started = entry != null
            if (entry != null) current + (key to entry.copy(status = PendingRemoval.Status.Committing)) else current
        }
        if (!started) return
        cancelTimer(key)
        scope.launch {
            val removed = runCatchingPreservingCancellation { commit(key) }.getOrDefault(false)
            if (removed) entries.update { it - key } else fail(key)
        }
    }

    private fun fail(key: K) {
        entries.update { current ->
            current[key]?.let { current + (key to it.copy(status = PendingRemoval.Status.Failed)) } ?: current
        }
        schedule(key, DEFAULT_UNDO_DURATION) { undo(key) }
    }

    private fun schedule(key: K, duration: Duration, onEnd: () -> Unit) {
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(UndoTimeouts.stretch(duration))
            synchronized(lock) { if (timers[key] === coroutineContext[Job]) timers.remove(key) }
            onEnd()
        }
        synchronized(lock) { timers.put(key, timer) }?.cancel()
        timer.start()
    }

    private fun cancelTimer(key: K) {
        synchronized(lock) { timers.remove(key) }?.cancel()
    }
}
