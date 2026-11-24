package com.theoriacodex.domain.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
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
        assertTrue(sole.isBusy)

        gate.complete(Unit)
        assertFalse(sole.isBusy)
        request()
        assertEquals(2, runs)
    }

    @Test fun `a cancelled job leaves room for the next launch`() = runTest {
        val parent = SupervisorJob(coroutineContext.job)
        val sole = SoleJob(CoroutineScope(coroutineContext + parent + UnconfinedTestDispatcher(testScheduler)))
        var runs = 0
        sole.launchIfIdle {
            runs += 1
            awaitCancellation()
        }
        sole.launchIfIdle { runs += 1 }
        assertEquals(1, runs)

        parent.cancelChildren()
        assertFalse(sole.isBusy)
        sole.launchIfIdle { runs += 1 }
        assertEquals(2, runs)
        parent.cancel()
    }
}
