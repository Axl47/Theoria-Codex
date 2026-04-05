package com.theoriacodex.app.search

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScreenMediaBehaviorTest {
    @Test
    fun `iwara cards do not allow inline autoplay in search`() {
        assertFalse(allowsInlineAutoplayInSearch(samplePost(SourceKey.IWARA)))
    }

    @Test
    fun `rule34 video family cards allow inline autoplay in search`() {
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.RULE34VIDEO)))
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.RULE34GEN)))
    }

    @Test
    fun `non iwara sources keep inline autoplay enabled in search`() {
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.PIXIV)))
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.GELBOORU)))
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.NHENTAI)))
    }

    private fun samplePost(source: SourceKey): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "1"),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
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
