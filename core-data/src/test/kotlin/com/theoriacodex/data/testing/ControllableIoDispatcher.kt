package com.theoriacodex.data.testing

import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal class ControllableIoDispatcher(
    threadName: String,
) : CoroutineDispatcher(), Closeable {
    private val delegate: ExecutorCoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        .asCoroutineDispatcher()

    @Volatile
    var dispatchFailure: RuntimeException? = null

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchFailure?.let { failure -> throw failure }
        delegate.dispatch(context, block)
    }

    override fun close() {
        delegate.close()
    }
}
