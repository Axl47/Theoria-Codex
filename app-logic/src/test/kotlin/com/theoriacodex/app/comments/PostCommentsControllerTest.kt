package com.theoriacodex.app.comments

import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostCommentsControllerTest {
    private val post = commentsTestPost("1")
    private val pages = mapOf(
        "/" to commentPage("a", "b", total = 3, next = "p2"),
        "/p2" to commentPage("c"),
        "a/" to commentPage("r1", next = "r2"),
        "a/r2" to commentPage("r2"),
    )

    private fun TestScope.controller(loader: FakeCommentsLoader, gate: suspend () -> Unit = {}) =
        PostCommentsController(loader, this, gate)

    @Test
    fun `the current post's first page loads once and swiping back asks for nothing`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader)

        controller.show(post)
        controller.show(commentsTestPost("2", CommentHint.None))
        controller.show(post)

        assertEquals(listOf("1:/"), loader.requests)
        assertEquals(listOf("a", "b"), controller.state.value.getValue(post.id).top.comments.map { it.id })
        assertNull(controller.state.value[commentsTestPost("2").id])
    }

    @Test
    fun `nothing loads for a source without comments or a listing that says none`() = runTest(UnconfinedTestDispatcher()) {
        val unsupported = FakeCommentsLoader(pages, supported = false)
        controller(unsupported).show(post)
        val none = FakeCommentsLoader(pages)
        controller(none).show(commentsTestPost(hint = CommentHint.None))
        assertTrue(unsupported.requests.isEmpty() && none.requests.isEmpty())
        // A listing that only says "some", or says nothing, is still asked.
        val asked = FakeCommentsLoader(pages)
        controller(asked).apply { show(commentsTestPost("x", CommentHint.Some)); show(commentsTestPost("y")) }
        assertEquals(2, asked.requests.size)
    }

    @Test
    fun `the background request waits for the gate`() = runTest(UnconfinedTestDispatcher()) {
        val open = CompletableDeferred<Unit>()
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader) { open.await() }

        controller.show(post)
        assertTrue(loader.requests.isEmpty())
        open.complete(Unit)
        assertEquals(listOf("1:/"), loader.requests)
    }

    @Test
    fun `more comments continue from the page token and a failure retries the same page`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader)
        controller.show(post)

        loader.failNext = true
        controller.loadMore(post)
        val failed = controller.state.value.getValue(post.id).top
        assertEquals(CommentLoadStatus.Failed, failed.status)
        assertEquals(listOf("a", "b"), failed.comments.map { it.id })

        controller.loadMore(post)
        val top = controller.state.value.getValue(post.id).top
        assertEquals(listOf("a", "b", "c"), top.comments.map { it.id })
        assertFalse(top.hasMore)
        assertEquals(listOf("1:/", "1:/p2", "1:/p2"), loader.requests)

        controller.loadMore(post)
        assertEquals(3, loader.requests.size)
    }

    @Test
    fun `a failed first page is retried from the start`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages).apply { failNext = true }
        val controller = controller(loader)
        controller.show(post)
        assertEquals(CommentLoadStatus.Failed, controller.state.value.getValue(post.id).top.status)

        controller.loadMore(post)
        assertEquals(listOf("a", "b"), controller.state.value.getValue(post.id).top.comments.map { it.id })
        assertEquals(listOf("1:/", "1:/"), loader.requests)
    }

    @Test
    fun `replies load on first opening, stay loaded when closed, and page on their own`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader)
        controller.show(post)
        val parent = testComment("a", hasReplies = true)

        controller.toggleReplies(post, parent)
        assertEquals(setOf("a"), controller.state.value.getValue(post.id).expandedReplies)
        assertEquals(listOf("r1"), controller.state.value.getValue(post.id).replies.getValue("a").comments.map { it.id })

        controller.toggleReplies(post, parent)
        controller.toggleReplies(post, parent)
        controller.loadMoreReplies(post, parent)
        val state = controller.state.value.getValue(post.id)
        assertEquals(listOf("r1", "r2"), state.replies.getValue("a").comments.map { it.id })
        assertEquals(listOf("1:/", "1:a/", "1:a/r2"), loader.requests)
        assertEquals(listOf("a", "b"), state.top.comments.map { it.id })
    }

    @Test
    fun `reset forgets every post`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader)
        controller.show(post)
        controller.reset()
        assertTrue(controller.state.value.isEmpty())
        controller.show(post)
        assertEquals(2, loader.requests.size)
    }

    @Test
    fun `focusing a top-level comment pages until it appears and then acts once`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader)
        controller.show(post)

        controller.focus(post, CommentTarget("c"))

        assertEquals(listOf("1:/", "1:/p2"), loader.requests)
        assertEquals("comment:c", controller.state.value.getValue(post.id).focusKey)
        controller.focusShown(post)
        assertNull(controller.state.value.getValue(post.id).focusKey)
    }

    @Test
    fun `focusing a reply opens its parent's replies and pages them`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages)
        val controller = controller(loader)

        controller.focus(post, CommentTarget("r2", parentId = "a"))

        val state = controller.state.value.getValue(post.id)
        assertEquals(listOf("1:/", "1:a/", "1:a/r2"), loader.requests)
        assertEquals("reply:a:r2", state.focusKey)
        assertEquals(setOf("a"), state.expandedReplies)
    }

    @Test
    fun `a comment that is not there leaves the post's comments as they were`() = runTest(UnconfinedTestDispatcher()) {
        val missing = FakeCommentsLoader(pages)
        val controller = controller(missing)
        controller.focus(post, CommentTarget("nope"))
        assertEquals(listOf("1:/", "1:/p2"), missing.requests)
        assertNull(controller.state.value.getValue(post.id).focusKey)

        val missingReply = FakeCommentsLoader(pages)
        val replyController = controller(missingReply)
        replyController.focus(post, CommentTarget("nope", parentId = "a"))
        val state = replyController.state.value.getValue(post.id)
        assertNull(state.focusKey)
        assertTrue(state.expandedReplies.isEmpty())
    }

    @Test
    fun `the search stops after a bounded number of pages and after a failed one`() = runTest(UnconfinedTestDispatcher()) {
        val endless = (0..20).associate { page ->
            (if (page == 0) "/" else "/p$page") to commentPage("x$page", next = "p${page + 1}")
        }
        val bounded = FakeCommentsLoader(endless)
        controller(bounded).focus(post, CommentTarget("never"))
        assertEquals(10, bounded.requests.size)

        val failing = FakeCommentsLoader(pages).apply { failNext = true }
        val controller = controller(failing)
        controller.focus(post, CommentTarget("c"))
        assertEquals(1, failing.requests.size)
        assertNull(controller.state.value.getValue(post.id).focusKey)
    }

    @Test
    fun `nothing is searched for where the source has no comments`() = runTest(UnconfinedTestDispatcher()) {
        val loader = FakeCommentsLoader(pages, supported = false)
        controller(loader).focus(post, CommentTarget("a"))
        assertTrue(loader.requests.isEmpty())
    }

    @Test
    fun `links use the site's anchor for top-level comments only and need a page url`() = runTest(UnconfinedTestDispatcher()) {
        val withUrl = commentsTestPost("1", pageUrl = "https://example.com/post/1")
        val controller = controller(FakeCommentsLoader(pages, anchors = mapOf("a" to "c123")))

        assertEquals("https://example.com/post/1#c123", controller.linkTo(withUrl, CommentTarget("a")))
        assertEquals("https://example.com/post/1#theoria-comment=5", controller.linkTo(withUrl, CommentTarget("5")))
        assertEquals("https://example.com/post/1#theoria-comment=a/7", controller.linkTo(withUrl, CommentTarget("7", parentId = "a")))
        assertNull(controller.linkTo(post, CommentTarget("a")))
    }
}
