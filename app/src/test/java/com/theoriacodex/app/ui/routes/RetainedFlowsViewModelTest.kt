package com.theoriacodex.app.ui.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedFlowsViewModelTest {
    private var created = 0
    private val source: () -> Flow<Any> = {
        created += 1
        flowOf("value")
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an evicted idle flow is released and starts over when requested again`() {
        val flows = RetainedFlowsViewModel(maxFlows = 1)
        val evicted = flows.retain("a", source)

        flows.retain("b", source)

        assertNotSame(evicted, flows.retain("a", source))
    }

    @Test
    fun `a flow on screen is never evicted and keeps its value`() = runTest(UnconfinedTestDispatcher()) {
        val flows = RetainedFlowsViewModel(maxFlows = 1)
        val onScreen = flows.retain("a", source)
        val collector = launch { onScreen.collect { } }

        flows.retain("b", source)

        assertSame(onScreen, flows.retain("a", source))
        assertEquals("value", onScreen.value)
        assertEquals(1, created)
        collector.cancel()
    }
}
