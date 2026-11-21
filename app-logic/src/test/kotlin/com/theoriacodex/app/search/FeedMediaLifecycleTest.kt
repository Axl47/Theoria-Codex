package com.theoriacodex.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedMediaLifecycleTest {
    @Test
    fun `feed decode size follows card geometry with explicit ceilings`() {
        assertEquals(
            FeedPreviewDecodeSize(widthPx = 540, heightPx = 960),
            feedPreviewDecodeSize(screenWidthPx = 1_080, aspectRatio = 0.5625f),
        )
        assertEquals(
            FeedPreviewDecodeSize(widthPx = 1_600, heightPx = 2_400),
            feedPreviewDecodeSize(screenWidthPx = 8_000, aspectRatio = 0.1f),
        )
        assertEquals(
            FeedPreviewDecodeSize(widthPx = 500, heightPx = 500),
            feedPreviewDecodeSize(screenWidthPx = 1_000, aspectRatio = Float.NaN),
        )
    }
}
