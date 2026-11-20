package com.theoriacodex.app.media

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkReconnectionsTest {
    @Test fun `only a return from offline counts as reconnecting`() = runTest {
        var returns = 0
        flowOf(true, false, false, true, true, false, true).reconnections().collect { returns++ }
        assertEquals(2, returns)
    }
}
