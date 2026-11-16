package com.theoriacodex.app.ui.routes

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.testing.SteppedDispatcher
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        assertEquals(posts.take(2), owner.session.value?.posts)

        background.drain()
        runCurrent()
        assertEquals(posts.take(3), owner.session.value?.posts)
    }

    @Test fun `a session that changed during the background work is merged without repeats`() = runTest {
        val owner = viewer(this)
        launch { appendLiveSourcePosts(owner, source, background) }
        runCurrent()
        owner.updateSession { it.copy(posts = it.posts + posts[2]) }

        background.drain()
        runCurrent()
        assertEquals(posts.take(3), owner.session.value?.posts)
    }

    private fun viewer(scope: TestScope) = ViewerViewModel(SavedStateHandle(), scopeOverride = scope.backgroundScope).apply {
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
