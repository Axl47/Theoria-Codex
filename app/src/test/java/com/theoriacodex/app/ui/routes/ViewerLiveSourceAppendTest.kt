package com.theoriacodex.app.ui.routes

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.testing.SteppedDispatcher
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.testViewerOwner
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A live session grows with its source's feed; the whole-list work runs off the thread that applies it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerLiveSourceAppendTest {
    private val posts = (1..4).map { testPost(SourceKey.PIXIV, "p$it") }
    private val background = SteppedDispatcher()

    // The feed has loaded p3 and p4; p4 is liked, and the launch hides liked posts.
    private val source = ViewerRouteLiveSourceState(
        search = ViewerRouteLiveSourceSnapshot(queryHash = "q", results = posts),
        likedPostIds = setOf(posts[3].id),
    )

    @Test fun `the feed is filtered and matched in the background, then only what the session lacks is appended`() = runTest {
        val owner = viewer(this)
        launch { appendLiveSourcePosts(owner, source, background) }
        runCurrent()
        assertEquals(posts.take(2), owner.state.value.pages.map { it.post })

        background.drain()
        runCurrent()
        assertEquals(posts.take(3), owner.state.value.pages.map { it.post })
    }

    @Test fun `a session that changed during the background work is merged without repeats`() = runTest {
        val owner = viewer(this)
        launch { appendLiveSourcePosts(owner, source, background) }
        runCurrent()
        owner.appendSessionPosts(owner.session.value!!, owner.state.value.pages, listOf(posts[2]))

        background.drain()
        runCurrent()
        assertEquals(posts.take(3), owner.state.value.pages.map { it.post })
    }

    @Test fun `an append builds pages for the new posts only and keeps the current post`() = runTest {
        val owner = viewer(this)
        owner.onAction(ViewerAction.SelectPage(1))
        val before = owner.state.value.pages
        launch { appendLiveSourcePosts(owner, source, background) }
        runCurrent()
        background.drain()
        runCurrent()

        val after = owner.state.value
        assertEquals(posts.take(3).map { it.id }, after.pages.map { it.post.id })
        before.forEachIndexed { index, page -> assertSame(page, after.pages[index]) }
        assertEquals(1, after.currentPageIndex)
    }

    @Test fun `a session replaced during the background work gets nothing appended`() = runTest {
        val owner = viewer(this)
        launch { appendLiveSourcePosts(owner, source, background) }
        runCurrent()
        val replacement = owner.session.value!!.copy(sessionId = "other", posts = posts.take(1))
        owner.replaceSession(replacement)

        background.drain()
        runCurrent()
        assertEquals(posts.take(1), owner.state.value.pages.map { it.post })
        assertEquals(listOf(posts[0].id), owner.state.value.pages.map { it.post.id })
    }

    private fun viewer(scope: TestScope) = testViewerOwner(SavedStateHandle(), scopeOverride = scope.backgroundScope).apply {
        replaceSession(
            ViewerSession(
                posts = posts.take(2),
                context = ViewerLaunchContext(queryHash = "q", startIndex = 0, streamSource = ViewerStreamSource.SEARCH, scrollOffsetHint = 0),
                liveSearchBinding = true,
                searchVisibilityFilters = SearchVisibilityFilters(hideLiked = true),
            ),
        )
    }
}
