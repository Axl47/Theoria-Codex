package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SoleJob(private val scope: CoroutineScope) {
    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    fun launchIfIdle(block: suspend CoroutineScope.() -> Unit) {
        if (isActive) return
        val next = scope.launch(start = CoroutineStart.LAZY, block = block)
        job = next
        next.start()
    }
}
