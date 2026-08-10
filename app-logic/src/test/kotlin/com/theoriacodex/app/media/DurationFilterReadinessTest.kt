package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurationFilterReadinessTest {
    @Test
    fun activeFilterWaitsForUnknownAnimatedDecisionsOnly() {
        val pending = post("pending", animated = true)
        val unsupported = post("unsupported", animated = true)
        val known = post("known", animated = true, durationMs = 4_000L)
        val static = post("static", animated = false)

        val readiness = durationFilterReadiness(
            posts = listOf(pending, unsupported, known, static),
            durationFilterActive = true,
            stateByPostId = mapOf(
                unsupported.id to MediaDurationState.Unsupported(
                    MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA,
                ),
            ),
        )

        assertEquals(2, readiness.candidateCount)
        assertEquals(1, readiness.pendingCount)
        assertTrue(readiness.isResolving)
    }

    @Test
    fun inactiveFilterNeverBlocksPaging() {
        val readiness = durationFilterReadiness(
            posts = listOf(post("pending", animated = true)),
            durationFilterActive = false,
            stateByPostId = emptyMap(),
        )

        assertFalse(readiness.isResolving)
    }

    private fun post(id: String, animated: Boolean, durationMs: Long? = null): Post {
        val ref = ImageRef(
            url = "https://media.example/$id.${if (animated) "mp4" else "jpg"}",
            localPath = null,
            mime = if (animated) "video/mp4" else "image/jpeg",
        )
        return Post(
            id = PostId(SourceKey.HITOMI, id),
            preview = ref,
            full = ref,
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            durationMs = durationMs,
        )
    }
}
