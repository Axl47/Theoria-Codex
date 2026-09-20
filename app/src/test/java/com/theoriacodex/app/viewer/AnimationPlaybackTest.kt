package com.theoriacodex.app.viewer

import androidx.compose.runtime.BroadcastFrameClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnimationPlaybackTest {
    @Test
    fun `buffering cannot complete early or skip elapsed animation on resume`() {
        val clock = LoopingAnimationClock(1_000L)
        clock.advanceTo(0L, 1f, false, availableUntilMs = 200L)
        assertFalse(clock.advanceTo(2_000_000_000L, 1f, false, availableUntilMs = 200L))
        assertEquals(199L, clock.positionMs)
        assertFalse(clock.advanceTo(9_000_000_000L, 1f, false, availableUntilMs = 200L))
        clock.advanceTo(9_010_000_000L, 1f, false)
        assertEquals(209L, clock.positionMs)
        assertTrue(clock.advanceTo(10_010_000_000L, 1f, false))
    }

    @Test
    fun `seek beyond buffered frames waits at the requested position`() {
        val clock = LoopingAnimationClock(1_000L)
        clock.seekTo(700L)
        clock.advanceTo(0L, 1f, availableUntilMs = 200L)
        clock.advanceTo(1_000_000_000L, 1f, availableUntilMs = 200L)
        assertEquals(700L, clock.positionMs)
        clock.advanceTo(1_050_000_000L, 1f)
        assertEquals(750L, clock.positionMs)
    }

    @Test
    fun `play once completes exactly once and seeking allows replay`() {
        val clock = LoopingAnimationClock(1_000L)
        assertFalse(clock.advanceTo(0L, 1f, false))
        assertTrue(clock.advanceTo(2_000_000_000L, 1f, false))
        assertEquals(999L, clock.positionMs)
        assertFalse(clock.advanceTo(3_000_000_000L, 1f, false))
        clock.seekTo(0L)
        assertFalse(clock.advanceTo(4_000_000_000L, 1f, false))
        assertTrue(clock.advanceTo(5_000_000_000L, 1f, false))
    }

    @Test
    fun `late frames retain elapsed time and loop remainder`() {
        val clock = LoopingAnimationClock(1_000L)
        clock.advanceTo(0L, 1f)
        clock.advanceTo(250_000_000L, 1f)
        assertEquals(250L, clock.positionMs)
        clock.advanceTo(2_375_000_000L, 1f)
        assertEquals(375L, clock.positionMs)
    }

    @Test
    fun `slow rates retain fractional milliseconds across frames`() {
        val clock = LoopingAnimationClock(1_000L)
        clock.advanceTo(0L, 0.5f)
        repeat(10) { frame -> clock.advanceTo((frame + 1) * 1_000_000L, 0.5f) }
        assertEquals(5L, clock.positionMs)
    }

    @Test
    fun `pause seek restart and rate changes reset the time anchor`() {
        val clock = LoopingAnimationClock(1_000L)
        clock.advanceTo(0L, 1f)
        clock.advanceTo(100_000_000L, 1f)
        clock.resetFrameTime()
        clock.advanceTo(10_000_000_000L, 2f)
        assertEquals(100L, clock.positionMs)
        clock.advanceTo(10_100_000_000L, 2f)
        assertEquals(300L, clock.positionMs)
        clock.seekTo(900L)
        clock.advanceTo(20_000_000_000L, 1f)
        assertEquals(900L, clock.positionMs)
        clock.advanceTo(20_150_000_000L, 1f)
        assertEquals(50L, clock.positionMs)
        clock.seekTo(0L)
        assertEquals(0L, clock.positionMs)
    }

    @Test
    fun `frame selection respects uneven durations and exact boundaries`() {
        val ends = longArrayOf(20L, 120L, 150L)
        assertEquals(0, animationFrameIndexAt(0L, ends))
        assertEquals(0, animationFrameIndexAt(19L, ends))
        assertEquals(1, animationFrameIndexAt(20L, ends))
        assertEquals(1, animationFrameIndexAt(119L, ends))
        assertEquals(2, animationFrameIndexAt(120L, ends))
        assertEquals(2, animationFrameIndexAt(150L, ends))
    }

    @Test
    fun `stopped lifecycle cancels frame work and resume excludes background time`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this)
            }
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            val frames = BroadcastFrameClock()
            val state = AnimationPlaybackState(1_000L)
            val job = launch(frames) { animatePlaybackWhileStarted(state, owner.lifecycle, 1f) }
            runCurrent()
            assertFalse(frames.hasAwaiters)

            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertTrue(frames.hasAwaiters)
            frames.sendFrame(0L)
            runCurrent()
            frames.sendFrame(100_000_000L)
            runCurrent()
            assertEquals(100L, state.positionMs)

            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertFalse(frames.hasAwaiters)
            frames.sendFrame(5_000_000_000L)
            assertEquals(100L, state.positionMs)

            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            frames.sendFrame(10_000_000_000L)
            runCurrent()
            assertEquals(100L, state.positionMs)
            frames.sendFrame(10_050_000_000L)
            runCurrent()
            assertEquals(150L, state.positionMs)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            job.join()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
