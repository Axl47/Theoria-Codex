package com.theoriacodex.app.testing

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

class SteppedDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += block
    }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    fun drainNewestFirst() {
        val queued = queue.reversed()
        queue.clear()
        queued.forEach(Runnable::run)
        drain()
    }
}
