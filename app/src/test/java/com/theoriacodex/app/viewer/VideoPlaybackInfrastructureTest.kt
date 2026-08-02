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
        assertEquals(64L * 1024L * 1024L, VIDEO_PLAYBACK_CACHE_MAX_BYTES)
        assertEquals(6 * 1024 * 1024, FEED_PREVIEW_TARGET_BUFFER_BYTES)
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
}
