package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedVideoPositionHandoffTest {
    private val key = ViewerMediaKey(PostId(SourceKey.RULE34VIDEO, "video"), 0)

    @Test
    fun `viewer exit before feed binding seeks once to the latest position including zero`() {
        val handoff = FeedVideoPositionHandoff()
        val seeks = mutableListOf<Long>()
        handoff.publish(key, 12_000L)
        handoff.publish(key, 0L)
        handoff.bind(key, seeks::add).close()
        handoff.bind(key, seeks::add).close()
        assertEquals(listOf(0L), seeks)
    }

    @Test
    fun `late viewer disposal reaches active feed but never a recycled player`() {
        val handoff = FeedVideoPositionHandoff()
        val seeks = mutableListOf<Long>()
        val binding = handoff.bind(key, seeks::add)
        handoff.publish(key, 24_000L)
        binding.close()
        handoff.publish(key, 30_000L)
        assertEquals(listOf(24_000L), seeks)
        val replacementSeeks = mutableListOf<Long>()
        handoff.bind(key, replacementSeeks::add).close()
        assertEquals(listOf(30_000L), replacementSeeks)
    }

    @Test
    fun `other posts and gallery items do not consume the handoff`() {
        val handoff = FeedVideoPositionHandoff()
        val seeks = mutableListOf<Long>()
        handoff.publish(key, 42_000L)
        handoff.bind(key.copy(mediaIndex = 1), seeks::add).close()
        handoff.bind(key.copy(postId = PostId(SourceKey.GELBOORU, "video")), seeks::add).close()
        assertEquals(emptyList<Long>(), seeks)
        handoff.bind(key, seeks::add).close()
        assertEquals(listOf(42_000L), seeks)
    }
}
