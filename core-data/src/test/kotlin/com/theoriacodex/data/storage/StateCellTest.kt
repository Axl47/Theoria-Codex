package com.theoriacodex.data.storage

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StateCellTest {
    @Test
    fun `a failed or cancelled write keeps the value and preserves the failure`() = runTest {
        listOf(IOException("write failed"), CancellationException("cancelled")).forEach { expected ->
            val cell = FileStateCell(initial = "persisted") { throw expected }

            val failure = runCatching { cell.update { "ahead" } }.exceptionOrNull()

            assertEquals(expected, failure)
            assertEquals("persisted", cell.value())
            assertEquals("persisted", cell.data.first())
        }
    }

    @Test
    fun `an unchanged value is not written`() = runTest {
        val writes = mutableListOf<String>()
        val cell = FileStateCell(load = { "stored" }) { writes += it }

        cell.update { it }
        cell.update { "next" }

        assertEquals(listOf("next"), writes)
    }
}
