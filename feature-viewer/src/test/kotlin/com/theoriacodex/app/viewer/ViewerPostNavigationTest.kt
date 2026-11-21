package com.theoriacodex.app.viewer

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ViewerPostNavigationTest {
    private val originals = listOf("before", "seed", "next", "after").map { testPost(sourcePostId = it) }
    private val recommendations = listOf("r1", "r2").map { testPost(sourcePostId = it) }
    private val launch = ViewerSession(originals, ViewerLaunchContext("query", 1, ViewerStreamSource.SEARCH, 0), sessionId = "order")
    private val session = launched(launch).including(originals[1], recommendations)

    @Test fun `original next ignores recommendations while the branch ends at that same original next`() {
        val source = state(session, originals[1])
        val root = viewerPostNavigation(source)
        assertEquals(originals, root.originalPosts)
        assertEquals(originals, root.pages.map { it.post })
        assertEquals(originals[2], root.pages[root.currentIndex + 1].post)
        val branch = viewerPostNavigation(state(session, recommendations.last()))
        assertEquals(listOf(originals[1]) + recommendations + originals[2], branch.pages.map { it.post })
        assertEquals(originals[2], branch.pages[branch.currentIndex + 1].post)
        assertEquals(1, branch.stripAnchorIndex)
        assertEquals(originals[1], viewerPostNavigation(state(session, recommendations.first())).pages.first().post)
    }

    @Test fun `new source pages stay in original order and never promote branch members into the bottom strip`() {
        val nextPage = testPost(sourcePostId = "new-page")
        val paged = session.appending(nextPage)
        assertEquals(originals + nextPage, viewerPostNavigation(state(paged, recommendations.last())).originalPosts)
        val selectedOriginal = viewerPostNavigation(state(paged, recommendations.last()), pendingPostId = nextPage.id)
        assertEquals(nextPage, selectedOriginal.pages.last().post)
    }

    @Test fun `liking a recommendation keeps its ancestor and original return target`() {
        val newer = testPost(sourcePostId = "new-related")
        val chain = session.including(recommendations.first(), listOf(newer))
        val navigation = viewerPostNavigation(state(chain, newer))
        assertEquals(originals, navigation.originalPosts)
        assertEquals(listOf(originals[1], recommendations.first(), newer, originals[2]), navigation.pages.map { it.post })
        assertEquals(originals[2], navigation.pages[navigation.currentIndex + 1].post)
    }

    @Test fun `recommendations at the original end have no next until another source page arrives`() {
        val end = launched(ViewerSession(listOf(originals[1]), launch.context.copy(startIndex = 0)))
            .including(originals[1], recommendations)
        val branch = viewerPostNavigation(state(end, recommendations.last()))
        assertEquals(branch.pages.lastIndex, branch.currentIndex)
        val paged = end.appending(originals[2])
        val extended = viewerPostNavigation(state(paged, recommendations.last()))
        assertEquals(originals[2], extended.pages[extended.currentIndex + 1].post)
    }

    @Test fun `next playback mode also respects original order and branch exit`() {
        fun complete(post: Post): ViewerUiState {
            val current = state(session, post).let { it.copy(controls = it.controls.copy(
                playbackMode = ViewerPlaybackMode.NEXT, playback = it.controls.playback.copy(playing = true),
            )) }
            return reduceViewerState(current, ViewerAction.PlaybackCompleted(
                requireNotNull(current.session), requireNotNull(current.currentMedia).key, current.currentMedia!!.loadGeneration,
            )).state
        }
        assertEquals(originals[2].id, complete(originals[1]).currentPage?.post?.id)
        assertEquals(originals[2].id, complete(recommendations.last()).currentPage?.post?.id)
        assertFalse(complete(originals.last()).controls.playback.playing)
    }

    private fun launched(session: ViewerSession) = reduceViewerState(ViewerUiState.Empty, session.toReplaceViewerSessionAction()).state

    private fun ViewerUiState.including(seed: Post, posts: List<Post>) =
        reduceViewerState(this, ViewerAction.IncludeRelatedPosts(requireNotNull(session), seed.id, posts)).state

    private fun ViewerUiState.appending(post: Post) =
        reduceViewerState(this, ViewerAction.AppendPosts(requireNotNull(session), listOf(post))).state

    private fun state(source: ViewerUiState, selected: Post): ViewerUiState =
        reduceViewerState(source, ViewerAction.SelectPage(source.pages.indexOfFirst { it.post.id == selected.id })).state
}
