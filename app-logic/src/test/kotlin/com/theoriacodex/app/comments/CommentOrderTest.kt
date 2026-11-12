package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentOrder
import com.theoriacodex.domain.model.PostComment
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentOrderTest {
    private fun comment(id: String, at: Long?) = PostComment(id, "author", null, "text", at)
    private val comments = listOf(comment("b", 2), comment("none", null), comment("a", 1), comment("c", 3))

    @Test
    fun `oldest first puts undated comments last`() {
        assertEquals(listOf("a", "b", "c", "none"), comments.inOrder(CommentOrder.OLDEST).map { it.id })
    }

    @Test
    fun `newest first puts undated comments last`() {
        assertEquals(listOf("c", "b", "a", "none"), comments.inOrder(CommentOrder.NEWEST).map { it.id })
    }
}
