package com.theoriacodex.app.search

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedAutoplayTraceTest {
    private val stalled = FeedAutoplayGates(
        onScreen = true, focused = true, inWindow = false, inFeed = null, started = true,
        expectsVideo = true, hasVideo = true, playbackFailed = false, leaseWanted = false, leased = false,
    )

    @Test
    fun `a video card without a player is written once after the stall window, again when a gate changes, and when it clears (ADR 0103)`() {
        val trace = FeedAutoplayStallTrace("GELBOORU:1")

        assertNull(trace.observe(stalled, nowMs = 0L))
        assertNull(trace.observe(stalled, nowMs = FEED_AUTOPLAY_STALL_MS - 1))
        assertEquals("GELBOORU:1 stalled $FEED_AUTOPLAY_STALL_MS ms $stalled", trace.observe(stalled, nowMs = FEED_AUTOPLAY_STALL_MS))
        assertNull(trace.observe(stalled, nowMs = FEED_AUTOPLAY_STALL_MS + 500))

        val moved = stalled.copy(inWindow = true)
        assertEquals("GELBOORU:1 stalled ${FEED_AUTOPLAY_STALL_MS + 1_000} ms $moved", trace.observe(moved, nowMs = FEED_AUTOPLAY_STALL_MS + 1_000))
        assertEquals("GELBOORU:1 left while stalled", trace.close())

        val playing = moved.copy(leased = true)
        assertEquals("GELBOORU:1 cleared $playing", trace.observe(playing, nowMs = FEED_AUTOPLAY_STALL_MS + 1_500))
        assertNull(trace.observe(playing, nowMs = FEED_AUTOPLAY_STALL_MS + 2_000))
        assertNull(trace.close())
    }

    @Test
    fun `cards that are playing, off screen, behind another window or still images write nothing (ADR 0103)`() {
        listOf(
            stalled.copy(leased = true),
            stalled.copy(onScreen = false),
            stalled.copy(focused = false),
            stalled.copy(expectsVideo = false, hasVideo = false),
        ).forEach { gates ->
            val trace = FeedAutoplayStallTrace("GELBOORU:1")
            assertNull(trace.observe(gates, nowMs = 0L))
            assertNull(trace.observe(gates, nowMs = FEED_AUTOPLAY_STALL_MS * 10))
            assertNull(trace.close())
        }
    }

    @Test
    fun `a video whose link has not arrived yet still counts as a card that should play (ADR 0103)`() {
        val poster = ImageRef(url = "https://example.test/1.jpg", localPath = null, mime = "image/jpeg")
        val awaiting = Post(
            id = PostId(SourceKey.GELBOORU, "1"), preview = poster, full = null, pageUrl = null, width = 100, height = 100,
            canonicalTags = emptyList(), rawTags = emptyList(), authorName = null, createdAtEpochMs = null, durationMs = 5_000L,
        )

        assertTrue(awaiting.expectsFeedVideo())
        assertFalse(awaiting.copy(durationMs = null).expectsFeedVideo())
    }
}
