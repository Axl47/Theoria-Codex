package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private suspend fun suspendCancellation(error: CancellationException): Nothing {
        throw error
    }
}
