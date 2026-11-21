package com.theoriacodex.app.ui.undo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredRemovalsTest {
    private val committed = mutableListOf<String>()
    private var succeeds = true

    private fun TestScope.removals() = DeferredRemovals<String>(backgroundScope) { key -> committed += key; succeeds }

    private fun DeferredRemovals<String>.status(key: String) = pending.value[key]?.status

    @Test
    fun `several removals wait at once, each on its own timer`() = runTest {
        val removals = removals()
        removals.remove("a", "Removed a")
        advanceTimeBy(DEFAULT_UNDO_DURATION / 2)
        removals.remove("b", "Removed b")
        advanceTimeBy(DEFAULT_UNDO_DURATION / 2 + 1.milliseconds)
        assertEquals(listOf("a"), committed)
        assertEquals(setOf("b"), removals.pending.value.keys)
        advanceTimeBy(DEFAULT_UNDO_DURATION)
        assertEquals(listOf("a", "b"), committed)
        assertTrue(removals.pending.value.isEmpty())
    }

    @Test
    fun `undo puts the item back and cancels its commit`() = runTest {
        val removals = removals()
        removals.remove("a", "Removed a")
        removals.act("a")
        advanceTimeBy(1.hours)
        assertTrue(committed.isEmpty())
        assertTrue(removals.pending.value.isEmpty())
    }

    @Test
    fun `an until-left removal commits only when the surface is left`() = runTest {
        val removals = removals()
        removals.remove("codex", "Deleted Cats", UndoLifetime.UntilLeft)
        advanceTimeBy(1.hours)
        assertEquals(PendingRemoval.Status.Waiting, removals.status("codex"))
        removals.commitAll()
        runCurrent()
        assertEquals(listOf("codex"), committed)
        assertTrue(removals.pending.value.isEmpty())
    }

    @Test
    fun `a failed commit offers Retry, then shows the item again`() = runTest {
        val removals = removals()
        succeeds = false
        removals.remove("a", "Removed a", UndoLifetime.UntilLeft)
        removals.commitAll()
        runCurrent()
        assertEquals(PendingRemoval.Status.Failed, removals.status("a"))

        succeeds = true
        removals.act("a")
        runCurrent()
        assertEquals(listOf("a", "a"), committed)
        assertTrue(removals.pending.value.isEmpty())

        succeeds = false
        removals.remove("b", "Removed b", UndoLifetime.UntilLeft)
        removals.commitAll()
        runCurrent()
        advanceTimeBy(DEFAULT_UNDO_DURATION + 1.milliseconds)
        assertTrue("an unretried failure shows the item again", removals.pending.value.isEmpty())
    }

    @Test
    fun `a commit that throws counts as a failure`() = runTest {
        val removals = DeferredRemovals<String>(backgroundScope) { error("disk") }
        removals.remove("a", "Removed a", UndoLifetime.UntilLeft)
        removals.commitAll()
        runCurrent()
        assertEquals(PendingRemoval.Status.Failed, removals.status("a"))
    }
}
