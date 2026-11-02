package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationFilterProjectionTest {
    @Test fun `anchor visibility waits for preparation and its own duration decision only`() {
        val anchor = testPost(sourcePostId = "anchor").copy(
            full = ImageRef("https://example.test/anchor.mp4", null, "video/mp4"), media = emptyList(),
        )
        val other = anchor.copy(id = anchor.id.copy(sourcePostId = "other"))
        val posts = listOf(anchor, other)
        val projection = DurationFilterProjection()
        assertFalse(DurationFilterMetadata.RESOLVING.isVisibilityResolved(anchor, resolveInBackground = false))
        val pending = projection.project(posts, emptyMap(), true)
        assertFalse(pending.isVisibilityResolved(anchor, resolveInBackground = true))
        assertTrue(pending.isVisibilityResolved(anchor, resolveInBackground = false))
        val known = projection.project(posts, mapOf(mediaDurationKey(anchor) to
            MediaDurationState.Known(1_000, MediaDurationProvenance.CONTAINER_PROBE)), true)
        assertTrue(known.readiness.isResolving)
        assertTrue(known.isVisibilityResolved(anchor, resolveInBackground = true))
        val failed = projection.project(posts, mapOf(mediaDurationKey(anchor) to
            MediaDurationState.RetryableFailure(1_000, MediaDurationFailureReason.TIMEOUT)), true)
        assertTrue(failed.isVisibilityResolved(anchor, resolveInBackground = true))
    }

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
