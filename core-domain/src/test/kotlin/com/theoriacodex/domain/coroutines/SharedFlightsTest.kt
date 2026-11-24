package com.theoriacodex.domain.coroutines

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `overlapping callers share one request and a later caller starts a new one (ADR 0002)`() = runTest {
        val flights = SharedFlights<String, Int>()
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val first = async { flights.share(KEY) { fetches++; gate.await(); fetches } }
        val second = async { flights.share(KEY) { fetches++; -1 } }
        runCurrent()
        gate.complete(Unit)

        assertEquals(listOf(1, 1), listOf(first.await(), second.await()))
        assertEquals(2, flights.share(KEY) { ++fetches })
    }

    @Test
    fun `a caller after every waiter left starts a new request while the old one winds down (ADR 0002)`() = runTest {
        val flights = SharedFlights<String, String>()
        val windingDown = CompletableDeferred<Unit>()
        val leaving = launch { flights.share(KEY) { withContext(NonCancellable) { windingDown.await() }; "abandoned" } }
        runCurrent()
        leaving.cancel()
        runCurrent()

        val fresh = async { flights.share(KEY) { "fresh" } }
        runCurrent()
        windingDown.complete(Unit)
        fresh.join()

        assertFalse("the new caller joined the abandoned request", fresh.isCancelled)
        assertEquals("fresh", fresh.await())
    }

    @Test
    fun `the request is cancelled only once every waiter has left (ADR 0002)`() = runTest {
        val flights = SharedFlights<String, String>()
        var cancelled = false
        val first = launch { flights.share(KEY) { try { awaitCancellation() } finally { cancelled = true } } }
        val second = launch { flights.share(KEY) { "unused" } }
        runCurrent()

        first.cancel()
        runCurrent()
        assertFalse("one waiter leaving cancelled the request", cancelled)
        second.cancel()
        runCurrent()
        assertTrue("the request outlived its last waiter", cancelled)
    }

    private companion object {
        const val KEY = "post"
    }
}
