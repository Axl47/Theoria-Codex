package com.theoriacodex.data.testing

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal class RecordingIoDispatcher(
    threadName: String,
) : CoroutineDispatcher(), Closeable {
    private val delegate: ExecutorCoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        .asCoroutineDispatcher()
    private val recordedThreadNames = ConcurrentLinkedQueue<String>()

    val executionThreadNames: List<String>
        get() = recordedThreadNames.toList()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context) {
            recordedThreadNames += Thread.currentThread().name
            block.run()
        }
    }

    override fun close() {
        delegate.close()
    }
}
