package com.theoriacodex.app.viewer

import androidx.compose.ui.unit.dp
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
}
