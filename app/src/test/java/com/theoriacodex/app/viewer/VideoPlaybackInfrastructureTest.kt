package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackInfrastructureTest {
    @Test
    fun `bindings reuse one shared resource while retaining request identity`() {
        val sharedFactory = Any()
        val pool = SharedVideoResourcePool(sharedFactory)

        val first = pool.bind(
            location = "https://media.example/preview.mp4",
            headers = mapOf("Referer" to "https://first.example/"),
        )
        val second = pool.bind(
            location = "https://media.example/other.mp4",
            headers = mapOf("Referer" to "https://second.example/"),
        )

        assertSame(sharedFactory, first.sharedResource)
        assertSame(first.sharedResource, second.sharedResource)
        assertNotEquals(first.identity, second.identity)
    }

    @Test
    fun `cache identity is deterministic order independent and credential isolated`() {
        val location = "https://media.example/protected.mp4"
        val first = videoPlaybackIdentity(
            location = location,
            headers = linkedMapOf(
                "Referer" to "https://source.example/",
                "Authorization" to "Bearer secret-a",
            ),
        )
        val reordered = videoPlaybackIdentity(
            location = location,
            headers = linkedMapOf(
                "Authorization" to "Bearer secret-a",
                "Referer" to "https://source.example/",
            ),
        )
        val differentCredential = videoPlaybackIdentity(
            location = location,
            headers = mapOf(
                "Authorization" to "Bearer secret-b",
                "Referer" to "https://source.example/",
            ),
        )

        assertEquals(first, reordered)
        assertNotEquals(first.cacheKey, differentCredential.cacheKey)
        assertFalse(first.cacheKey.contains("secret-a"))
        assertFalse(first.cacheKey.contains(location))
        assertTrue(first.cacheKey.matches(Regex("theoria-video-v1-[0-9a-f]{64}")))
    }

    @Test
    fun `shared cache and each preview player have explicit byte ceilings`() {
        assertEquals(256L * 1024L * 1024L, VIDEO_PLAYBACK_CACHE_MAX_BYTES)
        assertEquals(2, FEED_PREVIEW_MAX_WARM_IDLE_PLAYERS)
        assertEquals(750L, FEED_PREVIEW_WARM_IDLE_GRACE_MS)
        assertEquals(60L, FEED_PREVIEW_PREPARE_SPACING_MS)
        assertEquals(2 * 1024 * 1024, FEED_PREVIEW_TARGET_BUFFER_BYTES)
        assertEquals(6_000, FEED_PREVIEW_MIN_BUFFER_MS)
        assertEquals(12_000, FEED_PREVIEW_MAX_BUFFER_MS)
        assertEquals(750, FEED_PREVIEW_PLAYBACK_BUFFER_MS)
        assertEquals(1_500, FEED_PREVIEW_REBUFFER_MS)
    }

    @Test
    fun `each concurrent preview player receives fresh load-control state`() {
        val first = VideoLoadControlFactory.create(VideoPlaybackProfile.FEED_PREVIEW)
        val second = VideoLoadControlFactory.create(VideoPlaybackProfile.FEED_PREVIEW)

        assertNotSame(first, second)
    }

    @Test
    fun `visible leases remain concurrent while returned slots are reused by identity`() {
        var nextResource = 0
        var nowMs = 1_000L
        val pool = ReusableVideoSlotPool<PreviewResource, String>(
            maxIdleResources = 2,
            idleTimeoutMs = 30_000L,
            clock = { nowMs },
            createResource = { PreviewResource(nextResource++) },
        )

        val first = pool.acquire("first")
        val second = pool.acquire("second")

        assertNotSame(first.resource, second.resource)
        assertEquals(2, pool.activeResourceCount)
        assertTrue(pool.recycle(first))
        assertFalse(pool.isActive(first))

        val firstAgain = pool.acquire("first")
        assertSame(first.resource, firstAgain.resource)
        assertFalse(firstAgain.requiresBinding)
        assertTrue(pool.isActive(firstAgain))
        assertFalse(pool.isActive(first))
        assertEquals(2, pool.activeResourceCount)
        assertEquals(2, pool.totalResourceCount)
        assertEquals(null, pool.pollExpired())
    }

    @Test
    fun `idle retention bound never caps simultaneous visible players`() {
        var nextResource = 0
        val pool = ReusableVideoSlotPool<PreviewResource, String>(
            maxIdleResources = 2,
            idleTimeoutMs = 30_000L,
            clock = { 0L },
            createResource = { PreviewResource(nextResource++) },
        )

        val visible = List(10) { index -> pool.acquire("visible-$index") }

        assertEquals(10, visible.map { lease -> lease.resource }.distinct().size)
        assertEquals(10, pool.activeResourceCount)
        assertEquals(null, pool.nextCleanupDelayMs())
    }

    @Test
    fun `invalidated slots rebind and idle cleanup releases one resource per poll`() {
        var nextResource = 0
        var nowMs = 0L
        val pool = ReusableVideoSlotPool<PreviewResource, String>(
            maxIdleResources = 1,
            idleTimeoutMs = 30_000L,
            clock = { nowMs },
            createResource = { PreviewResource(nextResource++) },
        )
        val first = pool.acquire("first")
        val second = pool.acquire("second")
        val third = pool.acquire("third")

        assertTrue(pool.recycle(first, retainBinding = false))
        assertTrue(pool.recycle(second))
        assertTrue(pool.recycle(third))
        assertEquals(3, pool.idleResourceCount)

        val releasedFirst = pool.pollExpired()
        assertSame(first.resource, releasedFirst)
        assertEquals(2, pool.idleResourceCount)
        val rebound = pool.acquire("first")
        assertTrue(rebound.requiresBinding)
        assertNotSame(first.resource, rebound.resource)

        assertTrue(pool.recycle(rebound))
        nowMs = 30_000L
        assertTrue(pool.pollExpired() != null)
        assertEquals(1, pool.idleResourceCount)
    }

    @Test
    fun `excess idle bindings cool while visible resources remain active`() {
        var nextResource = 0
        var nowMs = 0L
        val pool = ReusableVideoSlotPool<PreviewResource, String>(
            maxIdleResources = 8,
            idleTimeoutMs = 30_000L,
            clock = { nowMs },
            createResource = { PreviewResource(nextResource++) },
        )
        val first = pool.acquire("first")
        val second = pool.acquire("second")
        val third = pool.acquire("third")
        val visible = pool.acquire("visible")

        assertTrue(pool.recycle(first))
        assertTrue(pool.recycle(second))
        assertTrue(pool.recycle(third))
        assertEquals(1, pool.activeResourceCount)
        assertEquals(750L, pool.nextBindingClearDelayMs(2, 750L))
        assertEquals(null, pool.pollIdleBindingToClear(2, 750L))

        nowMs = 750L
        assertSame(first.resource, pool.pollIdleBindingToClear(2, 750L))
        assertEquals(null, pool.nextBindingClearDelayMs(2, 750L))
        val rebound = pool.acquire("first")
        assertSame(first.resource, rebound.resource)
        assertTrue(rebound.requiresBinding)
        assertEquals(2, pool.activeResourceCount)
        assertNotSame(visible.resource, rebound.resource)
    }

    private data class PreviewResource(val id: Int)
}
