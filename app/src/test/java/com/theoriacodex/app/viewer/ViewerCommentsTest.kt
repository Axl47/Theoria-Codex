package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.comments.CommentsLoading
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CommentHint
import com.theoriacodex.domain.model.CommentPage
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostComment
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerCommentsTest {
    private class RecordingLoader : CommentsLoading {
        val requested = mutableListOf<String>()
        override fun supports(source: SourceKey) = source == SourceKey.PIXIV
        override suspend fun load(post: Post, pageToken: String?, parent: PostComment?): CommentPage {
            requested += post.id.sourcePostId
            return CommentPage(listOf(PostComment("c", "who", null, "text", null)), total = 1, nextPageToken = null)
        }
    }

    private fun session(posts: List<Post>) = ViewerSession(
        posts = posts,
        context = ViewerLaunchContext(
            queryHash = "query", startIndex = 0, streamSource = ViewerStreamSource.SEARCH, scrollOffsetHint = 0,
        ),
        sessionId = "comments",
    )

    @Test
    fun `each post's comments load once it is current and swiping back asks for nothing`() = runTest {
        val loader = RecordingLoader()
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this, commentsLoader = loader)
        val posts = listOf(
            testPost(sourcePostId = "first"),
            testPost(sourcePostId = "none").copy(commentHint = CommentHint.None),
            testPost(source = SourceKey.GELBOORU, sourcePostId = "unsupported"),
            testPost(sourcePostId = "last"),
        )
        owner.replaceSession(session(posts))
        openFirstMedia(owner)

        // The neighbours are not asked for until they are current.
        assertEquals(listOf("first"), loader.requested)
        listOf(1, 2, 3, 0).forEach { owner.onAction(ViewerAction.SelectPage(it)) }
        runCurrent()

        assertEquals(listOf("first", "last"), loader.requested)
        assertEquals(setOf(posts[0].id, posts[3].id), owner.comments.state.value.keys)
        owner.clearSession()
        assertTrue(owner.comments.state.value.isEmpty())
    }

    @Test
    fun `a post resolved without a hint keeps the one its listing gave`() = runTest {
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this)
        val listed = testPost(sourcePostId = "p").copy(commentHint = CommentHint(4))
        owner.replaceSession(session(listOf(listed)))

        owner.applyResolvedPost(listed.copy(commentHint = null, title = "Resolved"))

        assertEquals(CommentHint(4), owner.session.value?.posts?.single()?.commentHint)
        assertEquals(CommentHint(4), owner.state.value.currentPage?.post?.commentHint)
        assertEquals("Resolved", owner.state.value.currentPage?.post?.title)
    }

    @Test
    fun `prelaunch resolution keeps the selected post's listing hint`() = runTest {
        val listed = testPost(sourcePostId = "p", preview = com.theoriacodex.domain.model.ImageRef(null, null, "image/jpeg"), full = null)
            .copy(commentHint = CommentHint.Some)
        val context = session(listOf(listed)).context

        val prepared = prepareViewerPostsForLaunch(listOf(listed), context) { listed.copy(commentHint = null, full = listed.preview.copy(url = "https://example.test/p.jpg")) }

        assertEquals(CommentHint.Some, prepared.single().commentHint)
        assertEquals("https://example.test/p.jpg", prepared.single().full?.url)
    }

    /** The current post's media is up, which is what lets background requests start. */
    private fun TestScope.openFirstMedia(owner: ViewerViewModel) {
        val state = owner.state.value
        val key = requireNotNull(state.currentMedia).key
        owner.onAction(ViewerAction.MediaReady(requireNotNull(state.session), key, 0L))
        runCurrent()
    }
}
