package com.theoriacodex.app.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a committed edit waits before its search runs: longer when one search asks several sources. */
data class LiveSearchDelays(
    val singleSourceMs: Long = 600L,
    val multiSourceMs: Long = 900L,
)

/**
 * Runs the draft a moment after each committed edit (a tag added, removed or excluded, a source or filter
 * changed), so results follow the query without a Search button. Typing alone never schedules a run. A newer
 * edit restarts the wait; the caller cancels the request in flight when it schedules.
 */
internal class LiveSearchScheduler(
    private val scope: CoroutineScope,
    private val delays: LiveSearchDelays,
    private val onWaitingChanged: (Boolean) -> Unit,
    private val run: () -> Unit,
) {
    private var waiting: Job? = null

    /** Restarts the wait for the draft's run; [singleSource] picks the shorter wait. */
    fun schedule(singleSource: Boolean) {
        waiting?.cancel()
        onWaitingChanged(true)
        waiting = scope.launch {
            delay(if (singleSource) delays.singleSourceMs else delays.multiSourceMs)
            waiting = null
            onWaitingChanged(false)
            run()
        }
    }

    /** Drops a waiting run; true when one was waiting, so the caller can run it now instead. */
    fun cancel(): Boolean {
        val job = waiting ?: return false
        waiting = null
        job.cancel()
        onWaitingChanged(false)
        return true
    }
}
