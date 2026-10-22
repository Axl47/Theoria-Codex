package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerVideoPositionsTest {
    private val posts = (0..12).map { PostId(SourceKey.RULE34VIDEO, "video-$it") }
    private fun key(index: Int, mediaIndex: Int = 0) = ViewerMediaKey(posts[index], mediaIndex)

    @Test
    fun `backward and forward revisits keep independently updated positions`() {
        val positions = ViewerVideoPositions()
        positions.retainAround(posts, 0)
        positions.save(key(0), 12_000L)
        positions.retainAround(posts, 1)
        positions.save(key(1), 24_000L)
        positions.retainAround(posts, 2)
        positions.save(key(2), 36_000L)
        positions.retainAround(posts, 1)
        assertEquals(24_000L, positions.position(key(1)))
        positions.save(key(1), 28_000L)
        positions.retainAround(posts, 0)
        assertEquals(12_000L, positions.position(key(0)))
        positions.retainAround(posts, 2)
        assertEquals(36_000L, positions.position(key(2)))
        assertEquals(28_000L, positions.position(key(1)))
    }

    @Test
    fun `five posts on both sides are retained and sixth posts are evicted`() {
        val positions = ViewerVideoPositions()
        positions.retainAround(posts, 5)
        (0..10).forEach { positions.save(key(it), 1_000L + it) }
        positions.retainAround(posts, 6)
        positions.save(key(11), 11_000L)
        assertEquals(0L, positions.position(key(0)))
        assertEquals(1_001L, positions.position(key(1)))
        assertEquals(11_000L, positions.position(key(11)))
        positions.retainAround(posts, 5)
        assertEquals(0L, positions.position(key(11)))
        assertEquals(1_010L, positions.position(key(10)))
        assertEquals(0L, positions.position(key(0)))
    }

    @Test
    fun `late release cannot restore an evicted position`() {
        val positions = ViewerVideoPositions()
        positions.retainAround(posts, 0)
        positions.save(key(0), 12_000L)
        positions.retainAround(posts, 6)
        positions.save(key(0), 13_000L)
        positions.retainAround(posts, 0)
        assertEquals(0L, positions.position(key(0)))
    }

    @Test
    fun `gallery items and Viewer sessions have independent positions`() {
        val positions = ViewerVideoPositions()
        positions.retainAround(posts, 0)
        positions.save(key(0), 12_000L)
        positions.save(key(0, 1), 24_000L)
        assertEquals(12_000L, positions.position(key(0)))
        assertEquals(24_000L, positions.position(key(0, 1)))
        assertEquals(0L, ViewerVideoPositions().position(key(0)))
    }
}
