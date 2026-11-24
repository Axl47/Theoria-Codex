package com.theoriacodex.app.media

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
        val pending = projection.project(posts, MediaDurationStates.EMPTY, true)
        assertFalse(pending.isVisibilityResolved(anchor, resolveInBackground = true))
        assertTrue(pending.isVisibilityResolved(anchor, resolveInBackground = false))
        val known = projection.project(posts, mediaDurationStates(posts, mapOf(mediaDurationKey(anchor) to
            MediaDurationState.Known(1_000, MediaDurationProvenance.CONTAINER_PROBE))), true)
        assertTrue(known.readiness.isResolving)
        assertTrue(known.isVisibilityResolved(anchor, resolveInBackground = true))
        val failed = projection.project(posts, mediaDurationStates(posts, mapOf(mediaDurationKey(anchor) to
            MediaDurationState.RetryableFailure(1_000, MediaDurationFailureReason.TIMEOUT))), true)
        assertTrue(failed.isVisibilityResolved(anchor, resolveInBackground = true))
    }

    @Test fun `decisions published for one snapshot do not answer a replacement of the same post (ADR 0106)`() {
        val projection = DurationFilterProjection()
        val post = testPost().copy(full = ImageRef("https://example.test/movie.mp4", null, "video/mp4"), media = emptyList())
        val posts = listOf(post)
        val decisions = mapOf(mediaDurationKey(post) to MediaDurationState.Known(1_000, MediaDurationProvenance.PROVIDER))
        val pending = projection.project(posts, MediaDurationStates.EMPTY, true)
        assertEquals(1, pending.readiness.pendingCount)
        val decided = projection.project(posts, mediaDurationStates(posts, decisions), true)
        assertEquals(0, decided.readiness.pendingCount)
        assertEquals(1_000L, decided.knownDurationMsByPostId[post.id])
        val replacement = post.copy(full = ImageRef("https://example.test/replaced.mp4", null, "video/mp4"))
        val fresh = projection.project(listOf(replacement), mediaDurationStates(posts, decisions), true)
        assertEquals(1, fresh.readiness.pendingCount)
        assertTrue(fresh.knownDurationMsByPostId.isEmpty())
    }

    @Test fun `a length that changes nothing shown keeps the presented list, and only an in-place length change renews its key (ADR 0106)`() {
        val posts = List(12) { testPost(SourceKey.RULE34VIDEO, "v$it") }
        val projection = DurationFilterProjection()
        fun known(vararg lengths: Pair<Int, Long>) = mediaDurationStates(posts, lengths.associate { (index, ms) ->
            mediaDurationKey(posts[index]) to MediaDurationState.Known(ms, MediaDurationProvenance.CONTAINER_PROBE)
        })
        val first = projection.project(posts, known(0 to 1_000), true)
        assertEquals(listOf(posts[0]), first.presentedPosts)

        val unrevealing = projection.project(posts, known(0 to 1_000, 5 to 1_000), true)
        assertSame(first.presentedPosts, unrevealing.presentedPosts)
        assertSame(first.presentedDurationsKey, unrevealing.presentedDurationsKey)

        val revealing = projection.project(posts, known(0 to 1_000, 1 to 1_000, 5 to 1_000), true)
        assertEquals(posts.take(2), revealing.presentedPosts)
        assertSame(first.presentedDurationsKey, revealing.presentedDurationsKey)

        val changed = projection.project(posts, known(0 to 2_000, 1 to 1_000, 5 to 1_000), true)
        assertSame(revealing.presentedPosts, changed.presentedPosts)
        assertNotSame(revealing.presentedDurationsKey, changed.presentedDurationsKey)
    }
}
