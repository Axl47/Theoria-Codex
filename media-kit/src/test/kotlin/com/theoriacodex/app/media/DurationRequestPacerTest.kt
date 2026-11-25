package com.theoriacodex.app.media

import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationRequestPacerTest {
    @Test
    fun `a source starts one lookup per interval (ADR 0104)`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime }, { 500L }, 15_000L)
        val starts = mutableListOf<Long>()

        repeat(3) { launch { pacer.awaitTurn(SourceKey.GELBOORU); starts += testScheduler.currentTime } }
        advanceUntilIdle()

        assertEquals(listOf(0L, 500L, 1_000L), starts)

        advanceTimeBy(5_000L)
        starts.clear()
        repeat(2) { launch { pacer.awaitTurn(SourceKey.GELBOORU); starts += testScheduler.currentTime } }
        advanceUntilIdle()

        assertEquals("After an idle spell the interval counts from the next start", listOf(6_000L, 6_500L), starts)
    }

    @Test
    fun `lookups already waiting honour a cooldown that starts later (ADR 0104)`() = runTest {
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
    fun `a cooldown on one source leaves the others running (ADR 0104)`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime }, { 0L }, 15_000L)

        pacer.onThrottled(SourceKey.GELBOORU)
        pacer.awaitTurn(SourceKey.HITOMI)

        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `pixiv spaces lookups half a second apart, custom sites are paced like gelbooru and CDN sources do not wait (ADR 0104)`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime })
        val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
        val starts = mutableMapOf<SourceKey, MutableList<Long>>()

        listOf(SourceKey.PIXIV, SourceKey.GELBOORU, custom, SourceKey.DANBOORU).forEach { source ->
            repeat(2) { launch { pacer.awaitTurn(source); starts.getOrPut(source) { mutableListOf() } += testScheduler.currentTime } }
        }
        advanceUntilIdle()

        assertEquals(listOf(0L, 500L), starts[SourceKey.PIXIV])
        assertTrue("Gelbooru spaces its lookups", starts.getValue(SourceKey.GELBOORU)[1] > 0L)
        assertEquals("Custom sites are paced like Gelbooru", starts[SourceKey.GELBOORU], starts[custom])
        assertEquals(listOf(0L, 0L), starts[SourceKey.DANBOORU])
    }
}
