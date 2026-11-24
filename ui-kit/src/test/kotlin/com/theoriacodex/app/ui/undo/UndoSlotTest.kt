package com.theoriacodex.app.ui.undo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class UndoSlotTest {
    private val events = mutableListOf<String>()
    private val slot = UndoSlot()

    private fun recorded(name: String, anchor: UndoAnchor = UndoAnchor("screen"), lifetime: UndoLifetime = UndoLifetime.Timed()) =
        UndoOffer(name, anchor, lifetime = lifetime, onExpire = { events += "$name expired" }) { events += "$name acted" }

    private fun TestScope.owner(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + Job())

    @Test
    fun `an offer shows only at the surface it is anchored to`() {
        val offer = recorded("removed", UndoAnchor("codex-detail", "a"))
        assertSame(offer, offer.shownAt(UndoAnchor("codex-detail", "a")))
        assertNull(offer.shownAt(UndoAnchor("codex-detail", "b")))
        assertNull(offer.shownAt(UndoAnchor("history", "a")))
        assertNull(null.shownAt(UndoAnchor("codex-detail", "a")))
    }

    @Test
    fun `a newer offer expires the one it replaces`() = runTest {
        val first = recorded("first")
        val second = recorded("second")
        slot.offer(owner(), first)
        slot.offer(owner(), second)
        runCurrent()
        assertEquals(listOf("first expired"), events)
        assertSame(second, slot.offer.value)
        slot.act(first)
        runCurrent()
        assertEquals("a replaced offer can no longer act", listOf("first expired"), events)
    }

    @Test
    fun `acting clears the offer and cancels its expiry`() = runTest {
        val offer = recorded("removed")
        slot.offer(owner(), offer)
        slot.act(offer)
        advanceTimeBy(1.hours)
        assertEquals(listOf("removed acted"), events)
        assertNull(slot.offer.value)
    }

    @Test
    fun `a timed offer expires after its duration`() = runTest {
        slot.offer(owner(), recorded("timed"))
        advanceTimeBy(DEFAULT_UNDO_DURATION - 1.milliseconds)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(2.milliseconds)
        assertEquals(listOf("timed expired"), events)
        assertNull(slot.offer.value)
    }

    @Test
    fun `an until-left offer expires only when its surface is left`() = runTest {
        slot.offer(owner(), recorded("deleted", UndoAnchor("library", "tile-3"), UndoLifetime.UntilLeft))
        advanceTimeBy(1.hours)
        slot.expire("history")
        runCurrent()
        assertTrue(events.isEmpty())
        slot.expire("library")
        runCurrent()
        assertEquals(listOf("deleted expired"), events)
        assertNull(slot.offer.value)
    }

    @Test
    fun `an owner scope that ends withdraws its offer without expiring it`() = runTest {
        val owner = owner()
        slot.offer(owner, recorded("file", lifetime = UndoLifetime.UntilLeft))
        owner.cancel()
        runCurrent()
        assertNull(slot.offer.value)
        assertTrue("an offer must not expire when its owner dies", events.isEmpty())
    }

    @Test
    fun `an offer from a cancelled scope leaves the live offer alone`() = runTest {
        val live = recorded("live")
        slot.offer(owner(), live)
        slot.offer(owner().apply { cancel() }, recorded("late"))
        runCurrent()
        assertSame(live, slot.offer.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `awaitAction reports whether the user acted`() = runTest {
        var acted: Boolean? = null
        backgroundScope.launch { acted = slot.awaitAction("Cleared", UndoAnchor("history"), UndoAction.Retry) }
        runCurrent()
        assertEquals(UndoAction.Retry, slot.offer.value?.action)
        slot.act(requireNotNull(slot.offer.value))
        runCurrent()
        assertEquals(true, acted)

        backgroundScope.launch { acted = slot.awaitAction("Cleared", UndoAnchor("history")) }
        runCurrent()
        advanceTimeBy(DEFAULT_UNDO_DURATION + 1.milliseconds)
        assertFalse(requireNotNull(acted))
    }
}
