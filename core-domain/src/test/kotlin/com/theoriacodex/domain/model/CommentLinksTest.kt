package com.theoriacodex.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentLinksTest {
    @Test
    fun `top-level comments use the native anchor and replies use Theoria's fragment`() {
        val page = "https://gelbooru.com/index.php?page=post&s=view&id=9#old"
        assertEquals("https://gelbooru.com/index.php?page=post&s=view&id=9#c42", commentLink(page, CommentTarget("42"), "c42"))
        assertEquals(
            "https://gelbooru.com/index.php?page=post&s=view&id=9#theoria-comment=42/43",
            commentLink(page, CommentTarget("43", parentId = "42"), "c42"),
        )
        assertEquals("https://www.pixiv.net/artworks/1#theoria-comment=5", commentLink("https://www.pixiv.net/artworks/1", CommentTarget("5"), null))
    }

    @Test
    fun `links round-trip and other fragments are ignored`() {
        listOf(CommentTarget("5"), CommentTarget("6", parentId = "5")).forEach { target ->
            val link = commentLink("https://example.com/p/1", target, null)
            assertEquals(target, parseCommentTarget(link.substringAfter('#')))
        }
        assertEquals(CommentTarget("42"), parseCommentTarget("c42"))
        assertEquals(CommentTarget("42"), parseCommentTarget("comment-42"))
        assertEquals(CommentTarget("42"), parseCommentTarget("comment_42"))
        listOf(null, "", "top", "c", "cabc", "theoria-comment=", "theoria-comment=a", "theoria-comment=1/2/3", "comments-1")
            .forEach { assertNull(it, parseCommentTarget(it)) }
    }
}
