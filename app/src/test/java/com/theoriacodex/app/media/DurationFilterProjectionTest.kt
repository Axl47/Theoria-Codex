package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFilterProjectionTest {
    @Test fun `duration publications reuse prepared media keys and a replacement snapshot prepares fresh authority`() {
        var keys = 0
        val projection = DurationFilterProjection { post -> keys++; mediaDurationKey(post) }
        val post = testPost().copy(full = ImageRef("https://example.test/movie.mp4", null, "video/mp4"), media = emptyList())
        val posts = listOf(post)
        val key = mediaDurationKey(post)
        val pending = projection.project(posts, emptyMap(), true)
        assertEquals(1, pending.readiness.pendingCount)
        val decided = projection.project(posts,
            mapOf(key to MediaDurationState.Known(1_000, MediaDurationProvenance.PROVIDER)), true)
        assertEquals(1, keys)
        assertEquals(0, decided.readiness.pendingCount)
        assertEquals(1_000L, decided.knownDurationMsByPostId[post.id])
        val replacement = post.copy(full = ImageRef("https://example.test/replaced.mp4", null, "video/mp4"))
        val fresh = projection.project(listOf(replacement),
            mapOf(key to MediaDurationState.Known(1_000, MediaDurationProvenance.PROVIDER)), true)
        assertEquals(2, keys)
        assertEquals(1, fresh.readiness.pendingCount)
        org.junit.Assert.assertTrue(fresh.knownDurationMsByPostId.isEmpty())
    }
}
