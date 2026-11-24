package com.theoriacodex.app.vision

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PictureModelHolderTest {
    private class Counting {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        fun open(): PictureModel {
            opens.incrementAndGet()
            return object : PictureModel {
                override fun analyze(picture: Bitmap): PictureAnalysis = error("not used")
                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }
    }

    private fun TestScope.holder(counting: Counting) =
        PictureModelHolder(CoroutineScope(StandardTestDispatcher(testScheduler)), idleMs = IDLE, open = counting::open)

    @Test
    fun `loads once for consecutive uses and closes after the idle delay (ADR 0096)`() = runTest {
        val counting = Counting()
        val holder = holder(counting)

        holder.use { }
        holder.use { }
        advanceTimeBy(IDLE - 1)
        runCurrent()
        assertEquals(1, counting.opens.get())
        assertEquals(0, counting.closes.get())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, counting.closes.get())
    }

    @Test
    fun `a use before the delay ends restarts it (ADR 0096)`() = runTest {
        val counting = Counting()
        val holder = holder(counting)

        holder.use { }
        advanceTimeBy(IDLE - 1)
        holder.use { }
        advanceTimeBy(IDLE - 1)
        runCurrent()
        assertEquals(0, counting.closes.get())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, counting.closes.get())
    }

    @Test
    fun `reopens after the idle close (ADR 0096)`() = runTest {
        val counting = Counting()
        val holder = holder(counting)

        holder.use { }
        advanceTimeBy(IDLE + 1)
        runCurrent()
        holder.use { }

        assertEquals(2, counting.opens.get())
        assertEquals(1, counting.closes.get())
    }

    @Test
    fun `release closes at once and the next use loads again (ADR 0096)`() = runTest {
        val counting = Counting()
        val holder = holder(counting)

        holder.use { }
        holder.release()
        assertEquals(1, counting.closes.get())

        advanceTimeBy(IDLE + 1)
        runCurrent()
        assertEquals(1, counting.closes.get())

        holder.use { }
        assertEquals(2, counting.opens.get())
    }

    @Test
    fun `concurrent uses run one at a time on one model (ADR 0096, 0097)`() = runTest {
        val counting = Counting()
        val holder = holder(counting)
        val running = AtomicInteger()
        val peak = AtomicInteger()

        List(3) {
            async {
                holder.use {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(30)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, peak.get())
        assertEquals(1, counting.opens.get())
    }

    private companion object {
        const val IDLE = 1_000L
    }
}
