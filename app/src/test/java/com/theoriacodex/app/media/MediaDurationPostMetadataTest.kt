package com.theoriacodex.app.media

import com.theoriacodex.app.testing.animatedTestPost
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDurationPostMetadataTest {
    @Test
    fun `route key map excludes static posts that cannot have duration metadata`() {
        val animated = animatedTestPost(sourcePostId = "animated")
        val static = testPost(sourcePostId = "static")

        assertEquals(
            mapOf(animated.id to mediaDurationKey(animated)),
            mediaDurationKeysByPostId(listOf(animated, static)),
        )
    }

    @Test
    fun `route snapshot computes animated keys once and separates known from candidates`() {
        val unknown = animatedTestPost(sourcePostId = "unknown")
        val known = animatedTestPost(sourcePostId = "known", durationMs = 7_000L)
        val static = testPost(sourcePostId = "static")
        var keyComputations = 0

        val snapshot = mediaDurationPostSnapshot(
            posts = listOf(unknown, known, static),
            keyFactory = { post ->
                keyComputations += 1
                mediaDurationKey(post)
            },
        )

        assertEquals(2, keyComputations)
        assertEquals(setOf(unknown.id, known.id, static.id), snapshot.postsById.keys)
        assertEquals(setOf(unknown.id, known.id), snapshot.keysByPostId.keys)
        assertEquals(setOf(mediaDurationKey(unknown)), snapshot.candidatesByKey.keys)
        assertEquals(mapOf(mediaDurationKey(known) to 7_000L), snapshot.knownDurationsByKey)
    }

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
