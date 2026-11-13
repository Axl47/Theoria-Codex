package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.keepingListingDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCommentsStateTest {
    private fun loaded(page: com.theoriacodex.domain.model.CommentPage) =
        PostCommentsState(top = CommentList().withPage(page))

    @Test
    fun `before anything loads the listing hint gives the count`() {
        assertEquals(CommentCount.Exact(12), commentCount(CommentHint(12), null))
        assertEquals(CommentCount.Some, commentCount(CommentHint.Some, null))
        assertNull(commentCount(CommentHint.None, null))
        assertNull(commentCount(null, null))
        // A request still in flight or failed has told us nothing new.
        assertEquals(CommentCount.Some, commentCount(CommentHint.Some, PostCommentsState()))
    }

    @Test
    fun `a loaded page's total wins over the hint`() {
        assertEquals(CommentCount.Exact(7), commentCount(CommentHint(12), loaded(commentPage("a", total = 7))))
        assertNull(commentCount(CommentHint.Some, loaded(commentPage(total = 0))))
    }

    @Test
    fun `without a total a single page counts its comments and a longer list reads as at least`() {
        assertEquals(CommentCount.Exact(2), commentCount(null, loaded(commentPage("a", "b"))))
        val more = loaded(commentPage("a", "b", next = "p2"))
        assertEquals(CommentCount.AtLeast(2), commentCount(null, more))
        assertEquals(CommentCount.Exact(40), commentCount(CommentHint(40), more))
        assertNull(commentCount(CommentHint.Some, loaded(commentPage())))
    }

    @Test
    fun `count text names the figure only when known`() {
        assertEquals("12", CommentCount.Exact(12).label)
        assertEquals("30+", CommentCount.AtLeast(30).label)
        assertNull(CommentCount.Some.label)
        assertEquals("1 comment", CommentCount.Exact(1).description("Comments"))
        assertEquals("12 comments", CommentCount.Exact(12).description("Comments"))
        assertEquals("30+ comments", CommentCount.AtLeast(30).description("Comments"))
        assertEquals("Has comments", CommentCount.Some.description("Has comments"))
    }

    @Test
    fun `an empty first page leaves no section while loading and failures keep one`() {
        assertTrue(PostCommentsState().hasSection)
        assertTrue(PostCommentsState(top = CommentList().failed()).hasSection)
        assertFalse(loaded(commentPage()).hasSection)
        assertTrue(loaded(commentPage("a")).hasSection)
    }

    @Test
    fun `pages append without repeating a comment and a failure keeps what loaded`() {
        val first = CommentList().withPage(commentPage("a", "b", next = "p2"))
        val second = first.loading().withPage(commentPage("b", "c", total = 3))
        assertEquals(listOf("a", "b", "c"), second.comments.map { it.id })
        assertEquals(3, second.total)
        assertFalse(second.hasMore)
        val failed = first.loading().failed()
        assertEquals(listOf("a", "b"), failed.comments.map { it.id })
        assertEquals("p2", failed.nextPageToken)
    }

    @Test
    fun `reply button text`() {
        assertEquals("Replies", repliesLabel(null, expanded = false))
        assertEquals("1 reply", repliesLabel(1, expanded = false))
        assertEquals("4 replies", repliesLabel(4, expanded = false))
        assertEquals("Hide replies", repliesLabel(4, expanded = true))
    }

    @Test
    fun `a resolved post keeps the listing's hint only when it has none of its own`() {
        val listed = commentsTestPost(hint = CommentHint(3))
        val resolved = commentsTestPost()
        assertEquals(CommentHint(3), resolved.keepingListingDataOf(listed).commentHint)
        assertEquals(CommentHint.None, commentsTestPost(hint = CommentHint.None).keepingListingDataOf(listed).commentHint)
        val unhinted = commentsTestPost()
        assertSame(unhinted, unhinted.keepingListingDataOf(commentsTestPost()))
    }

    @Test
    fun `comment age is the shortest unit that fits`() {
        val now = 1_000_000_000_000L
        fun age(secondsAgo: Long) = commentAgeLabel(now, now - secondsAgo * 1000)
        assertEquals("now", age(30))
        assertEquals("5m", age(5 * 60))
        assertEquals("3h", age(3 * 3600))
        assertEquals("3d", age(3 * 86_400))
        assertEquals("2w", age(15 * 86_400))
        assertEquals("4mo", age(130 * 86_400))
        assertEquals("2y", age(800 * 86_400))
        assertEquals("now", commentAgeLabel(now, now + 5_000))
        assertNull(commentAgeLabel(now, null))
    }
}
