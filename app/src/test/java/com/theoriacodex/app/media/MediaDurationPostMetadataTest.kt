package com.theoriacodex.app.media

import com.theoriacodex.app.testing.animatedTestPost
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDurationPostMetadataTest {
    @Test
    fun `fingerprint ignores signed URL rotation but changes with authoritative media`() {
        val post = animatedTestPost(sourcePostId = "fingerprint")
        val signedRotation = post.copy(
            full = post.full?.copy(url = "https://example.test/fingerprint.mp4?token=rotated"),
        )
        val replacement = post.copy(
            full = post.full?.copy(url = "https://example.test/fingerprint-v2.mp4"),
        )

        assertEquals(mediaDurationKey(post), mediaDurationKey(signedRotation))
        assertNotEquals(mediaDurationKey(post), mediaDurationKey(replacement))
    }

    @Test
    fun `only the authoritative full media may publish player duration`() {
        val post = animatedTestPost(sourcePostId = "authority")

        assertTrue(isAuthoritativeDurationMedia(post, requireNotNull(post.full)))
        assertFalse(isAuthoritativeDurationMedia(post, post.preview))
    }

    @Test
    fun `coordinator metadata is read separately without rewriting the post`() {
        val post = animatedTestPost(sourcePostId = "separate")
        val state = MediaDurationState.Known(
            durationMs = 8_000L,
            provenance = MediaDurationProvenance.ACTIVE_PLAYER,
        )

        val durations = knownMediaDurations(
            posts = listOf(post),
            states = mapOf(mediaDurationKey(post) to state),
        )

        assertEquals(8_000L, durations[post.id])
        assertEquals(null, post.durationMs)
    }

    @Test
    fun `metadata for an old fingerprint is not reused`() {
        val oldPost = animatedTestPost(sourcePostId = "changed")
        val newPost = oldPost.copy(
            full = ImageRef(
                url = "https://example.test/changed-v2.mp4",
                localPath = null,
                mime = "video/mp4",
            ),
        )
        val oldState = MediaDurationState.Known(
            durationMs = 8_000L,
            provenance = MediaDurationProvenance.CONTAINER_PROBE,
        )

        assertTrue(
            knownMediaDurations(
                posts = listOf(newPost),
                states = mapOf(mediaDurationKey(oldPost) to oldState),
            ).isEmpty(),
        )
    }
}
