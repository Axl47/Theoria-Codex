package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SoleJobTest {
    @Test fun `a launch from inside the running job before it first suspends starts nothing (ADR 0135)`() = runTest {
        val sole = SoleJob(CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        fun request() {
            sole.launchIfIdle {
                runs += 1
                request()
                gate.await()
            }
        }

        request()
        assertEquals(1, runs)
        assertTrue(sole.isActive)

        gate.complete(Unit)
        assertFalse(sole.isActive)
        request()
        assertEquals(2, runs)
    }
}
