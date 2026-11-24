package com.theoriacodex.domain.coroutines

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class SharedFlightsTest {
    @Test
    fun `the request runs on the first caller's dispatcher under its own job (ADR 0002)`() = runBlocking {
        val flights = SharedFlights<String, Thread>()
        var firstThread: Thread? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        Executors.newSingleThreadExecutor { task -> Thread(task).also { firstThread = it } }.asCoroutineDispatcher().use { firstDispatcher ->
            val firstCaller = launch(firstDispatcher) {
                flights.share(KEY) {
                    started.complete(Unit)
                    release.await()
                    Thread.currentThread()
                }
            }
            started.await()
            val secondCaller = async(start = CoroutineStart.UNDISPATCHED) { flights.share(KEY) { Thread.currentThread() } }

            firstCaller.cancelAndJoin()
            release.complete(Unit)
            secondCaller.join()

            assertFalse("the request was cancelled with its first caller", secondCaller.isCancelled)
            assertSame(firstThread, secondCaller.await())
        }
    }

    private companion object {
        const val KEY = "post"
    }
}
