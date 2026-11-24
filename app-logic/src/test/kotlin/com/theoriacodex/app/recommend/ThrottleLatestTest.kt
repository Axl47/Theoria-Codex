package com.theoriacodex.app.recommend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottleLatestTest {
    @Test
    fun `the first value passes at once and a burst settles on its newest value once per period (ADR 0215)`() = runTest {
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Int>()
        backgroundScope.launch { source.throttleLatest(TAG_AFFINITY_STATISTICS_PERIOD).collect { seen += it } }
        runCurrent()
        assertEquals(listOf(0), seen)

        (1..30).forEach { view ->
            source.value = view
            advanceTimeBy(100)
        }
        assertEquals(listOf(0), seen)

        advanceTimeBy(TAG_AFFINITY_STATISTICS_PERIOD.inWholeMilliseconds)
        assertEquals(listOf(0, 30), seen)

        advanceTimeBy(TAG_AFFINITY_STATISTICS_PERIOD.inWholeMilliseconds * 2)
        assertEquals(listOf(0, 30), seen)
    }
}
