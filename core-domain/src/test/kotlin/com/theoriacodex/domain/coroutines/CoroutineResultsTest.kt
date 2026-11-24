package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineResultsTest {
    @Test
    fun `cancellation is rethrown unchanged`() = runTest {
        val expected = CancellationException("request superseded")
        var thrown: CancellationException? = null

        try {
            runCatchingPreservingCancellation {
                suspendCancellation(expected)
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(expected, thrown)
    }

    @Test
    fun `ordinary failure remains available as a result`() {
        val result = runCatchingPreservingCancellation<String> {
            error("provider unavailable")
        }

        assertTrue(result.isFailure)
        assertEquals("provider unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `exception variant rethrows cancellation and captures exceptions`() = runTest {
        val expected = CancellationException("request superseded")
        var thrown: CancellationException? = null

        try {
            runCatchingExceptionsPreservingCancellation { suspendCancellation(expected) }
        } catch (error: CancellationException) {
            thrown = error
        }
        val failure = runCatchingExceptionsPreservingCancellation<String> { error("provider unavailable") }

        assertSame(expected, thrown)
        assertEquals("provider unavailable", failure.exceptionOrNull()?.message)
    }

    @Test
    fun `exception variant lets errors propagate`() {
        val expected = OutOfMemoryError("heap")
        var thrown: Throwable? = null

        try {
            runCatchingExceptionsPreservingCancellation<String> { throw expected }
        } catch (error: Throwable) {
            thrown = error
        }

        assertSame(expected, thrown)
    }

    @Test
    fun `caller-only variant rethrows when the caller itself is cancelled`() = runTest {
        var result: Result<Unit>? = null
        val caller = launch { result = runCatchingUnlessCallerCancelled { awaitCancellation() } }
        runCurrent()

        caller.cancel()
        caller.join()

        assertTrue(caller.isCancelled)
        assertNull(result)
    }

    @Test
    fun `caller-only variant turns a timeout inside the block into a failure`() = runTest {
        val result = runCatchingUnlessCallerCancelled { withTimeout(10) { awaitCancellation() } }

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun `caller-only variant turns a cancelled dependency into a failure`() = runTest {
        val dependency = CompletableDeferred<String>()
        dependency.cancel(CancellationException("dependency stopped"))

        val result = runCatchingUnlessCallerCancelled { dependency.await() }

        assertEquals("dependency stopped", result.exceptionOrNull()?.message)
    }

    @Test
    fun `caller-only variant lets errors propagate`() = runTest {
        val expected = OutOfMemoryError("heap")
        var thrown: Throwable? = null

        try {
            runCatchingUnlessCallerCancelled<String> { throw expected }
        } catch (error: Throwable) {
            thrown = error
        }

        assertSame(expected, thrown)
    }

    private suspend fun suspendCancellation(error: CancellationException): Nothing {
        throw error
    }
}
