package com.theoriacodex.app.viewer

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerScrubPreviewTest {
    @Test fun `frames follow the media's shape`() {
        assertEquals(224.dp, viewerScrubFrameSize(16f / 9f).width)
        assertEquals(126f, viewerScrubFrameSize(16f / 9f).height.value, 0.5f)
        assertEquals(168.dp, viewerScrubFrameSize(9f / 16f).height)
        assertEquals(94.5f, viewerScrubFrameSize(9f / 16f).width.value, 0.5f)
    }

    @Test fun `extreme and unknown shapes stay visible`() {
        assertEquals(72.dp, viewerScrubFrameSize(10f).height)
        assertEquals(72.dp, viewerScrubFrameSize(0.1f).width)
        assertEquals(viewerScrubFrameSize(16f / 9f), viewerScrubFrameSize(null))
    }

    @Test fun `delta reads as distance from where the drag began`() {
        assertEquals("+0:13", formatScrubDelta(13_000L))
        assertEquals("-1:05", formatScrubDelta(-65_000L))
    }

    @Test fun `a drag's first target is extracted at once, later ones once the finger rests`() = runTest {
        val targets = MutableStateFlow(1_000L)
        val extracted = mutableListOf<Long>()
        val published = mutableListOf<Long>()
        val drag = launch { followScrubTargets(targets, { it.also(extracted::add) }) { target, _ -> published += target } }

        runCurrent()
        assertEquals(listOf(1_000L), published)
        // Passing over 2 s on the way to 3 s extracts only where the finger rests.
        targets.value = 2_000L
        advanceTimeBy(SCRUB_FRAME_SETTLE_MS / 2)
        targets.value = 3_000L
        advanceTimeBy(SCRUB_FRAME_SETTLE_MS)
        assertEquals(listOf(1_000L), extracted)
        advanceTimeBy(SCRUB_FRAME_SETTLE_MS)
        assertEquals(listOf(1_000L, 3_000L), extracted)
        assertEquals(listOf(1_000L, 3_000L), published)
        drag.cancel()
    }
}
