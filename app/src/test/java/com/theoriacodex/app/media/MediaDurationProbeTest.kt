package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDurationProbeTest {
    @Test
    fun `duration probe accepts authoritative full video media`() {
        val full = ImageRef("https://media.test/full.mp4", null, "video/mp4")

        assertEquals(full, authoritativeDurationProbeRef(post(full = full)))
    }

    @Test
    fun `duration probe rejects preview-only autoplay clips`() {
        val previewClip = ImageRef("https://media.test/preview.mp4", null, "video/mp4")

        assertNull(authoritativeDurationProbeRef(post(full = null, media = listOf(previewClip))))
    }

    @Test
    fun `duration probe recognizes extension when full media omits mime`() {
        val full = ImageRef("https://media.test/full.mp4?token=1", null, null)

        assertEquals(full, authoritativeDurationProbeRef(post(full = full)))
    }

    private fun post(
        full: ImageRef?,
        media: List<ImageRef> = full?.let(::listOf).orEmpty(),
    ): Post = Post(
        id = PostId(SourceKey.RULE34VIDEO, "1"),
        preview = ImageRef("https://media.test/preview.jpg", null, "image/jpeg"),
        full = full,
        media = media,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        title = null,
    )
}
