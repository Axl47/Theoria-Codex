package com.theoriacodex.app.search

import com.theoriacodex.data.repository.SearchScrollState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScrollResetTest {
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
