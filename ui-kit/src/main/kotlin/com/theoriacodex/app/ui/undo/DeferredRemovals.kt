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

/** One removal [DeferredRemovals] holds back: what its vacated row says, and where its commit stands. */
data class PendingRemoval(val label: String, val status: Status = Status.Waiting) {
    enum class Status {
        /** Undo still puts the item back. */
        Waiting,

        /** The commit is running; the row shows the label without an action. */
        Committing,

        /** The commit failed; the row offers Retry until it times out, then the item shows again. */
        Failed,
    }
}

/**
 * Deferred destruction for any number of items at once: [remove] hides an item now and deletes it through
 * [commit] only when its lifetime ends, so Undo is a plain un-hide and a process death deletes nothing.
 *
 * Each key has its own timer, independent of the [UndoSlot], so no unrelated offer commits a removal. A vacated row
 * or tile draws its Undo from [pending] (see [DeferredRemovalRow]). Commits and timers run in [scope], which must be
 * application-lived: the owning view model calls [commitAll] from `onCleared`, and that commit has to finish after
 * the view model is gone. [commit] returns false (or throws) when the item could not be removed; the item then
 * offers Retry for a while and shows again.
 */
class DeferredRemovals<K : Any>(
    private val scope: CoroutineScope,
    private val commit: suspend (K) -> Boolean,
) {
    private val entries = MutableStateFlow<Map<K, PendingRemoval>>(emptyMap())
    private val timers = mutableMapOf<K, Job>()
    private val lock = Any()

    /** Items hidden while their removal waits, commits, or offers Retry. */
    val pending: StateFlow<Map<K, PendingRemoval>> = entries.asStateFlow()

    /** Hides [key] at once; it is deleted when [lifetime] ends: after its timer, or when [commitAll] runs. */
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

    /** Puts [key] back: undoes a waiting removal, or dismisses a failed one. A running commit cannot be undone. */
    fun undo(key: K) {
        var removed = false
        entries.update { current ->
            removed = current[key]?.let { it.status != PendingRemoval.Status.Committing } == true
            if (removed) current - key else current
        }
        if (removed) cancelTimer(key)
    }

    /** Commits a failed removal again. */
    fun retry(key: K) {
        if (entries.value[key]?.status == PendingRemoval.Status.Failed) commitNow(key)
    }

    /** Runs the matching action for [key]'s row: Undo while it waits, Retry after a failure. */
    fun act(key: K) {
        if (entries.value[key]?.status == PendingRemoval.Status.Failed) retry(key) else undo(key)
    }

    /** Commits every waiting removal now, for a surface that was left or an owner being cleared; failed ones show again. */
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
