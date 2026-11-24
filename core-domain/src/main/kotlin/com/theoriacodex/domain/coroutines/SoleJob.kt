package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SoleJob(private val scope: CoroutineScope) {
    private val lock = Any()
    private var job: Job? = null

    val isActive: Boolean get() = synchronized(lock) { job.busy }

    fun launchIfIdle(block: suspend CoroutineScope.() -> Unit) {
        val next = synchronized(lock) {
            if (job.busy) return
            scope.launch(start = CoroutineStart.LAZY, block = block).also { job = it }
        }
        next.start()
    }

    private val Job?.busy: Boolean get() = this != null && !isCompleted && !isCancelled
}
