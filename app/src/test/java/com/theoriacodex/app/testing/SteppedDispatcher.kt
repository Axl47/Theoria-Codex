package com.theoriacodex.app.testing

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/** Queues background work until [drain] runs it on the test thread, so frame tests decide when it lands. */
internal class SteppedDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += block
    }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}
