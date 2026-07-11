package com.theoriacodex.data.storage

import com.theoriacodex.data.testing.RecordingIoDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationDataReadinessTest {
    @Test
    fun `initialization runs on the configured storage dispatcher`() = runTest {
        RecordingIoDispatcher("storage-readiness").use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val readiness = ApplicationDataReadiness(
                applicationScope = scope,
                initializationDispatcher = dispatcher,
            ) {
                Thread.currentThread().name
            }

            assertTrue(readiness.awaitReady().startsWith("storage-readiness"))
            assertTrue(
                dispatcher.executionThreadNames.all { threadName -> threadName.startsWith("storage-readiness") }
            )
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    @Test
    fun `construction is lazy and concurrent awaiters share one initialization`() = runTest {
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readiness = ApplicationDataReadiness(
            applicationScope = scope,
            initializationDispatcher = Dispatchers.Default,
        ) {
            calls.incrementAndGet()
            release.await()
            "repositories"
        }

        assertEquals(0, calls.get())
        assertTrue(readiness.state.value is ApplicationDataState.NotStarted)
        val awaiting = (0 until 20).map { async(Dispatchers.Default) { readiness.awaitReady() } }
        while (calls.get() == 0) kotlinx.coroutines.yield()
        assertEquals(1, calls.get())
        release.complete(Unit)

        assertEquals(List(20) { "repositories" }, awaiting.awaitAll())
        assertTrue(readiness.state.value is ApplicationDataState.Ready)
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    @Test
    fun `failure is sticky until explicit retry and retry publishes the recovered value`() = runTest {
        val calls = AtomicInteger()
        val expected = IllegalStateException("first attempt failed")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readiness = ApplicationDataReadiness(
            applicationScope = scope,
            initializationDispatcher = Dispatchers.Default,
        ) {
            if (calls.incrementAndGet() == 1) throw expected
            "ready"
        }

        val first = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { readiness.awaitReady() }
        }
        assertSame(expected, first)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { readiness.awaitReady() }
        }
        assertEquals(1, calls.get())

        assertEquals("ready", readiness.retry())
        assertEquals(2, calls.get())
        assertEquals("ready", (readiness.state.value as ApplicationDataState.Ready).value)
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    @Test
    fun `cancelling one waiter does not cancel process initialization`() = runTest {
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readiness = ApplicationDataReadiness(
            applicationScope = scope,
            initializationDispatcher = Dispatchers.Default,
        ) {
            release.await()
            42
        }
        val firstWaiter = async(Dispatchers.Default) { readiness.awaitReady() }
        while (readiness.state.value !is ApplicationDataState.Loading) kotlinx.coroutines.yield()

        firstWaiter.cancel()
        release.complete(Unit)

        assertEquals(42, readiness.awaitReady())
        scope.cancel()
        scope.coroutineContext.job.join()
    }
}
