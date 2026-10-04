package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SearchScrollState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScrollResetTest {
    @Test fun `closing on the UI thread can drain while a root reset finishes`() = runBlocking {
        val uiExecutor = Executors.newSingleThreadExecutor()
        val uiDispatcher = uiExecutor.asCoroutineDispatcher()
        val uiJob = SupervisorJob()
        val uiScope = CoroutineScope(uiJob + uiDispatcher)
        val enteredReset = CompletableDeferred<Unit>()
        val releaseReset = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val writes = ConcurrentLinkedQueue<String>()
        val controller = SearchScrollPersistenceController(Dispatchers.IO, 60_000) { writes += it.queryHash }
        var closing: Future<*>? = null
        try {
            controller.submit(SearchScrollPersistenceTarget("prior-query", SearchScrollState(12, 24)))
            val reset = uiScope.launch {
                controller.reset("next-query") {
                    withContext(NonCancellable) {
                        enteredReset.complete(Unit)
                        releaseReset.await()
                        writes += "reset"
                    }
                }
            }
            withTimeout(5_000) { enteredReset.await() }
            reset.cancel() // ViewModel teardown cancels route work before closing the flush owner.
            closing = uiExecutor.submit { closeStarted.complete(Unit); controller.close() }
            withTimeout(5_000) { closeStarted.await() }
            releaseReset.complete(Unit)
            closing.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("reset", "prior-query"), writes.toList())
        } finally {
            releaseReset.complete(Unit)
            closing?.cancel(true)
            try {
                withTimeout(5_000) { uiJob.cancelAndJoin() }
            } finally {
                uiDispatcher.close()
            }
        }
    }

    @Test fun `reset waits for an admitted write and permits the same position afterward`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<SearchScrollState>()
        val position = SearchScrollState(12, 24)
        val controller = SearchScrollPersistenceController(StandardTestDispatcher(testScheduler), 0) {
            gate.await()
            writes += it.state
        }
        controller.submit(SearchScrollPersistenceTarget("query", position))
        runCurrent()
        val reset = launch { controller.reset("query") { writes += SearchScrollState(0, 0) } }
        runCurrent()
        assertFalse(reset.isCompleted)
        gate.complete(Unit)
        runCurrent()
        controller.submit(SearchScrollPersistenceTarget("query", position))
        runCurrent()
        assertEquals(listOf(position, SearchScrollState(0, 0), position), writes)
        controller.close()
    }
}
