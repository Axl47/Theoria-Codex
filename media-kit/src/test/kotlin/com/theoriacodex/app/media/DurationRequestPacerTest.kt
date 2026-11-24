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
    fun `a source starts one lookup per interval (ADR 0104)`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime }, { 500L }, 15_000L)
        val starts = mutableListOf<Long>()

        repeat(3) { launch { pacer.awaitTurn(SourceKey.GELBOORU); starts += testScheduler.currentTime } }
        advanceUntilIdle()

        assertEquals(listOf(0L, 500L, 1_000L), starts)
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
    fun `pixiv and custom sites space lookups half a second apart like gelbooru while CDN sources do not wait (ADR 0104)`() = runTest {
        val pacer = DurationRequestPacer({ testScheduler.currentTime })
        val custom = requireNotNull(SourceKey.parse("custom_safebooru_org"))
        val starts = mutableMapOf<SourceKey, MutableList<Long>>()

        listOf(SourceKey.PIXIV, SourceKey.GELBOORU, custom, SourceKey.DANBOORU).forEach { source ->
            repeat(2) { launch { pacer.awaitTurn(source); starts.getOrPut(source) { mutableListOf() } += testScheduler.currentTime } }
        }
        advanceUntilIdle()

        assertEquals(
            mapOf(
                SourceKey.PIXIV to listOf(0L, 500L),
                SourceKey.GELBOORU to listOf(0L, 500L),
                custom to listOf(0L, 500L),
                SourceKey.DANBOORU to listOf(0L, 0L),
            ),
            starts,
        )
    }
}
