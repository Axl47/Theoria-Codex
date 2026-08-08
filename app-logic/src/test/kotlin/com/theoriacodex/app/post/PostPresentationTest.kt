package com.theoriacodex.app.post

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostPresentationTest {
    @Test
    fun `missing title stays absent instead of falling back to post id`() {
        assertNull(post(title = null).displayTitleOrNull())
        assertNull(post(title = "   ").displayTitleOrNull())
    }

    @Test
    fun `post id title is suppressed`() {
        assertNull(post(title = "  12345  ").displayTitleOrNull())
    }

    @Test
    fun `meaningful title is trimmed and retained`() {
        assertEquals("A meaningful title", post(title = "  A meaningful title  ").displayTitleOrNull())
    }

    private fun post(title: String?): Post = Post(
        id = PostId(source = SourceKey.GELBOORU, sourcePostId = "12345"),
        preview = ImageRef(url = null, localPath = null, mime = null),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        title = title,
    )
}
