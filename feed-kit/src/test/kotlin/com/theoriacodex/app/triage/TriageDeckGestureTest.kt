package com.theoriacodex.app.triage

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageDeckGestureTest {
    private val thresholds = SwipeThresholds(commitPx = 100f, flingPx = 700f, cuePx = 20f)
    private val area = Size(400f, 800f)
    private val decided = mutableListOf<TriageChoice>()

    @Test fun `a cancelled drag past the commit distance springs back and commits nothing`() = runTest {
        val drag = TriageDrag().apply { dragBy(Offset(250f, 0f), Offset(250f, 0f)) }

        withContext(StepClock) { drag.release(null, thresholds, area, { TriageChoice.Like }, decided::add) { error("no picker") } }

        assertEquals(Offset.Zero, drag.offset)
        assertTrue(decided.isEmpty())
    }

    @Test fun `a release past the commit distance commits its direction`() = runTest {
        val drag = TriageDrag().apply { dragBy(Offset(250f, 0f), Offset(250f, 0f)) }

        withContext(StepClock) { drag.release(Velocity.Zero, thresholds, area, { TriageChoice.Like }, decided::add) { error("no picker") } }

        assertEquals(listOf<TriageChoice>(TriageChoice.Like), decided)
    }

    /** Advances one 60 Hz frame per request, so animations run to their end without a UI. */
    private object StepClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            nanos += 16_000_000L
            return onFrame(nanos)
        }
    }
}
