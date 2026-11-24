package com.theoriacodex.app.ui.components

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.testing.waitUntilWithoutIdling
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OffMainComputationTest {
    @get:Rule val compose = createComposeRule()

    private data class Call(val list: List<String>, val input: String, val onMain: Boolean)

    /** One composition: what the helper was given and what it returned. */
    private data class Frame(val list: List<String>, val input: String, val value: List<String>)

    private var list by mutableStateOf(listOf("p1", "p2"))
    private var input by mutableStateOf("a")
    private val computes = CopyOnWriteArrayList<Call>()
    private val extends = CopyOnWriteArrayList<List<String>>()
    private val frames = CopyOnWriteArrayList<Frame>()

    /** When set, an off-main compute waits here; [entered] and [returning] mark where it is. */
    private var gate: CountDownLatch? = null
    private val entered = CountDownLatch(1)
    private val returning = CountDownLatch(1)
    @Volatile private var worker: Thread? = null

    private fun compute(list: List<String>, input: String): List<String> {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        computes += Call(list, input, onMain)
        if (!onMain) {
            worker = Thread.currentThread()
            entered.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            returning.countDown()
        }
        return list.map { "$it$input" }
    }

    /** Marks its items with `+`, so a value that went through [extend] is told apart from a full compute. */
    private fun extend(previous: List<String>, appended: List<String>, input: String): List<String> {
        extends += appended
        return previous + appended.map { "$it$input+" }
    }

    private fun show() = compose.setContent {
        val list = list
        val input = input
        val value = rememberComputedOffMain(list, input, extend = { previous ->
            list.appendedTo(previous.list)?.let { extend(previous.value, it, input) }
        }) { compute(list, input) }
        frames += Frame(list, input, value)
    }

    /** Lets the composition pick up a state change and launch its off-main compute, then waits for it to start. */
    private fun awaitOffMainCompute() {
        compose.waitForIdle()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
    }

    /** Releases the off-main compute (if gated) and waits until its result has been delivered or dropped. */
    private fun landOffMainCompute() {
        gate?.countDown()
        assertTrue(returning.await(5, TimeUnit.SECONDS))
        // A parked worker has finished resuming the helper's coroutine, which either ran it or queued it for
        // the composition's dispatcher; waitForIdle then drains that queue.
        compose.waitUntilWithoutIdling(5_000) { worker?.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) }
        compose.waitForIdle()
    }

    @Test
    fun `a replaced list is computed in the same composition`() {
        show()
        compose.runOnIdle { list = listOf("q1", "q2", "q3") }
        compose.waitForIdle()

        val replaced = frames.filter { it.list == listOf("q1", "q2", "q3") }
        assertTrue(replaced.isNotEmpty())
        assertTrue(replaced.all { it.value == listOf("q1a", "q2a", "q3a") })
        assertTrue(computes.none { !it.onMain })
    }

    @Test
    fun `an append with unchanged inputs extends only the appended items`() {
        show()
        compose.runOnIdle { list = list + listOf("p3", "p4") }
        compose.waitForIdle()

        assertEquals(listOf(listOf("p3", "p4")), extends.toList())
        assertEquals(1, computes.size)
        assertEquals(listOf("p1a", "p2a", "p3a+", "p4a+"), frames.last().value)
    }

    @Test
    fun `an input change keeps the previous value until the off-main result lands`() {
        show()
        compose.runOnIdle { input = "b" }
        awaitOffMainCompute()
        assertEquals(listOf("p1a", "p2a"), frames.first { it.input == "b" }.value)

        landOffMainCompute()

        assertEquals(listOf("p1b", "p2b"), frames.last().value)
        val recompute = computes.single { it.input == "b" }
        assertTrue(!recompute.onMain)
    }

    @Test
    fun `a list replaced during an off-main recompute keeps its in-place result`() {
        gate = CountDownLatch(1)
        show()
        compose.runOnIdle { input = "b" }
        awaitOffMainCompute()
        // An append, so a late answer for the old list that overwrote the cache would be extended into a `+` value.
        compose.runOnIdle { list = list + "p3" }
        compose.waitForIdle()
        assertEquals(listOf("p1b", "p2b", "p3b"), frames.last().value)

        landOffMainCompute()

        assertEquals(listOf("p1b", "p2b", "p3b"), frames.last().value)
        assertTrue(extends.isEmpty())
        assertEquals(2, computes.count { it.onMain })
    }

    @Test
    fun `an input changed and changed back before the recompute lands publishes nothing stale`() {
        gate = CountDownLatch(1)
        show()
        compose.runOnIdle { input = "b" }
        awaitOffMainCompute()
        compose.runOnIdle { input = "a" }
        compose.waitForIdle()
        val settled = frames.size

        landOffMainCompute()

        assertTrue(frames.all { it.value == listOf("p1a", "p2a") })
        assertEquals(settled, frames.size)
        assertNotEquals(0, computes.count { it.input == "b" })
    }
}
