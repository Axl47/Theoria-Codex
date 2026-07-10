package com.theoriacodex.data.storage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableMutationTest {
    @Test
    fun `failed persistence restores the previous state and preserves the failure`() = runTest {
        var state = "persisted"
        val expected = IOException("write failed")

        val failure = runCatching {
            mutateAndPersistWithRollback(
                snapshot = { state },
                restore = { previous -> state = previous },
                mutate = { state = "ahead" },
                persist = { throw expected },
            )
        }.exceptionOrNull()

        assertEquals(expected, failure)
        assertEquals("persisted", state)
    }

    @Test
    fun `cancelled persistence restores the previous state and preserves cancellation`() = runTest {
        var state = listOf("persisted")
        val expected = CancellationException("cancelled")

        val failure = runCatching {
            mutateAndPersistWithRollback(
                snapshot = { state },
                restore = { previous -> state = previous },
                mutate = { state = listOf("ahead") },
                persist = { throw expected },
            )
        }.exceptionOrNull()

        assertEquals(expected, failure)
        assertEquals(listOf("persisted"), state)
    }
}
