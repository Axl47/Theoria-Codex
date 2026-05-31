package com.theoriacodex.app.ui

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TheoriaAppMediaResolutionTest {
    @Test
    fun `requires lazy media resolution for unresolved iwara and rule34 video posts`() {
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.NHENTAI, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.IWARA, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.RULE34VIDEO, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.RULE34GEN, full = null, media = emptyList())))
    }

    @Test
    fun `does not require lazy media resolution when playable media already exists`() {
        assertFalse(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.IWARA,
                    full = ImageRef(url = "https://cdn.iwara.tv/video.mp4", localPath = null, mime = "video/mp4"),
                    media = emptyList(),
                ),
            ),
        )
        assertFalse(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.PIXIV,
                    full = null,
                    media = emptyList(),
                ),
            ),
        )
    }

    private fun samplePost(
        source: SourceKey,
        full: ImageRef?,
        media: List<ImageRef>,
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "1"),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = full,
            media = media,
            pageUrl = "https://example.com/post/1",
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
        )
    }
}
