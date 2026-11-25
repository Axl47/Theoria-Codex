package com.theoriacodex.app.media

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PostSnapshotUpdatesTest {
    @Test
    fun `a snapshot replaces its own card in place, admits nothing and keeps the listing's comment hint (ADR 0074)`() {
        val listed = listOf(post("a"), post("b").copy(commentHint = CommentHint(7)), post("c"))
        val detail = post("b").copy(title = "detail")

        val updated = listed.replacingPostSnapshot(detail)

        assertEquals(listOf("a", "b", "c"), updated.map { it.id.sourcePostId })
        assertEquals("detail", updated[1].title)
        assertEquals(CommentHint(7), updated[1].commentHint)
        assertEquals(listed[0], updated[0])
        assertEquals(listed[2], updated[2])
        assertSame(listed, listed.replacingPostSnapshot(post("unknown")))
    }

    private fun post(id: String): Post = Post(
        id = PostId(SourceKey.GELBOORU, id),
        preview = ImageRef("https://example.test/$id.jpg", null, "image/jpeg"),
        full = null,
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
