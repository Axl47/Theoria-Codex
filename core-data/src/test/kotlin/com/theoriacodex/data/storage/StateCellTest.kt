package com.theoriacodex.data.storage

import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StateCellTest {
    @Test
    fun `a failed or cancelled write keeps the value and preserves the failure (ADR 0035)`() = runTest {
        listOf(IOException("write failed"), CancellationException("cancelled")).forEach { expected ->
            val cell = FileStateCell(initial = "persisted") { throw expected }

            val failure = runCatching { cell.update { "ahead" } }.exceptionOrNull()

            assertEquals(expected, failure)
            assertEquals("persisted", cell.value())
            assertEquals("persisted", cell.data.first())
        }
    }

    @Test
    fun `an unchanged value is not written (ADR 0035)`() = runTest {
        val writes = mutableListOf<String>()
        val cell = FileStateCell(load = { "stored" }) { writes += it }

        cell.update { it }
        cell.update { "next" }

        assertEquals(listOf("next"), writes)
    }

    @Test
    fun `flushAll writes every cell it wrapped on its queue even when one fails (ADR 0215)`() = runTest {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, QUEUE_THREAD) }
        try {
            val queue = executor.asCoroutineDispatcher()
            val writeBehind = WriteBehind(this, timed = false, newQueue = { queue })
            val writes = mutableListOf<String>()
            val threads = mutableSetOf<String>()
            val record: suspend (String) -> Unit = { value -> threads += Thread.currentThread().name.substringBefore(" @"); writes += value }
            val positions = FileStateCell("old position", record).writeBehind(writeBehind)
            val failing = FileStateCell("old") { throw IOException("disk full") }.writeBehind(writeBehind, 10.seconds)
            val statistics = FileStateCell("old statistics", record).writeBehind(writeBehind, 10.seconds)
            positions.update { "new position" }
            failing.update { "new" }
            statistics.update { "new statistics" }

            val failure = runCatching { writeBehind.flushAll() }.exceptionOrNull()

            assertEquals("disk full", failure?.message)
            assertEquals(setOf("new position", "new statistics"), writes.toSet())
            assertEquals(setOf(QUEUE_THREAD), threads)
        } finally {
            executor.shutdown()
        }
    }

    private companion object {
        const val QUEUE_THREAD = "write-behind-test"
    }
}
