package com.theoriacodex.app.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LiveSearchDelays(
    val singleSourceMs: Long = 600L,
    val multiSourceMs: Long = 900L,
)

internal class LiveSearchScheduler(
    private val scope: CoroutineScope,
    private val delays: LiveSearchDelays,
    private val onWaitingChanged: (Boolean) -> Unit,
    private val run: () -> Unit,
) {
    private var waiting: Job? = null

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

    fun cancel(): Boolean {
        val job = waiting ?: return false
        waiting = null
        job.cancel()
        onWaitingChanged(false)
        return true
    }
}
