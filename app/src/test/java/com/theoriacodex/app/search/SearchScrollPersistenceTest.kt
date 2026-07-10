package com.theoriacodex.app.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScrollPersistenceTest {
    @Test
    fun `rapid scroll changes persist only the final stable position`() = runTest {
        val positions = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 8)
        val persisted = mutableListOf<Pair<Int, Int>>()
        val collector = backgroundScope.launch {
            positions.persistDebouncedSearchScrollStates(debounceMillis = 100L) { position ->
                persisted += position
            }
        }
        runCurrent()

        positions.emit(1 to 10)
        advanceTimeBy(40L)
        positions.emit(2 to 20)
        advanceTimeBy(40L)
        positions.emit(3 to 30)
        advanceTimeBy(99L)
        runCurrent()
        assertTrue(persisted.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(3 to 30), persisted)

        collector.cancel()
    }

    @Test
    fun `duplicate stable positions do not trigger another write`() = runTest {
        val positions = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 4)
        val persisted = mutableListOf<Pair<Int, Int>>()
        val collector = backgroundScope.launch {
            positions.persistDebouncedSearchScrollStates(debounceMillis = 50L) { position ->
                persisted += position
            }
        }
        runCurrent()

        positions.emit(4 to 12)
        advanceTimeBy(50L)
        runCurrent()
        positions.emit(4 to 12)
        advanceTimeBy(50L)
        runCurrent()

        assertEquals(listOf(4 to 12), persisted)
        collector.cancel()
    }

    @Test
    fun `disposing before debounce flushes the final observed position`() = runTest {
        val positions = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1)
        val persisted = mutableListOf<Pair<Int, Int>>()
        val collector = backgroundScope.launch {
            positions.persistDebouncedSearchScrollStates(debounceMillis = 500L) { position ->
                persisted += position
            }
        }
        runCurrent()

        positions.emit(8 to 24)
        runCurrent()
        collector.cancelAndJoin()

        assertEquals(listOf(8 to 24), persisted)
    }

    @Test
    fun `cancelling an in-flight commit retries the final position non-cancellably`() = runTest {
        val positions = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1)
        val firstCommitStarted = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Pair<Int, Int>>()
        var attempts = 0
        val collector = backgroundScope.launch {
            positions.persistDebouncedSearchScrollStates(debounceMillis = 0L) { position ->
                attempts += 1
                if (attempts == 1) {
                    firstCommitStarted.complete(Unit)
                    awaitCancellation()
                }
                persisted += position
            }
        }
        runCurrent()

        positions.emit(9 to 27)
        firstCommitStarted.await()
        collector.cancelAndJoin()

        assertEquals(2, attempts)
        assertEquals(listOf(9 to 27), persisted)
    }
}
