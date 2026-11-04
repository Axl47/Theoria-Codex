package com.theoriacodex.app.media

import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationRequestPacerTest {
    @Test
    fun `a source starts one lookup per interval`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime }, { 500L }, 15_000L)
        val starts = mutableListOf<Long>()

        repeat(3) { launch { pacer.awaitTurn(SourceKey.GELBOORU); starts += testScheduler.currentTime } }
        advanceUntilIdle()

        assertEquals(listOf(0L, 500L, 1_000L), starts)
    }

    @Test
    fun `lookups already waiting honour a cooldown that starts later`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime }, { 500L }, 15_000L)
        val starts = mutableListOf<Long>()

        repeat(2) { launch { pacer.awaitTurn(SourceKey.GELBOORU); starts += testScheduler.currentTime } }
        runCurrent()
        advanceTimeBy(100L)
        pacer.onThrottled(SourceKey.GELBOORU)
        advanceUntilIdle()

        assertEquals(listOf(0L, 15_100L), starts)
    }

    @Test
    fun `a cooldown on one source leaves the others running`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime }, { 0L }, 15_000L)

        pacer.onThrottled(SourceKey.GELBOORU)
        pacer.awaitTurn(SourceKey.HITOMI)

        assertEquals(0L, testScheduler.currentTime)
    }
}
