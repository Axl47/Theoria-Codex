package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.theoriacodex.app.related.RelatedPostsRequest
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerRelatedUiState
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerRelatedNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val image = ImageRef(null, "/missing/related-order.jpg", "image/jpeg")
    private val originals = listOf("before", "seed", "next", "after").map { testPost(sourcePostId = it).copy(preview = image, full = image, media = listOf(image)) }
    private val related = listOf("r1", "r2").map { originals[1].copy(id = originals[1].id.copy(sourcePostId = it)) }
    private lateinit var state: MutableState<ViewerUiState>

    @Test fun `last recommendation slides forward into the original next post without entering the bottom strip`() {
        showViewer()
        openRelated("r2")
        assertStripIsOriginalOnly()
        compose.mainClock.autoAdvance = false
        val actions = compose.onNodeWithContentDescription("Media actions")
        actions.performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.55f, centerY))
            moveTo(Offset(width * 0.55f, centerY))
        }
        compose.mainClock.advanceTimeBy(96)
        assertTrue("Next must enter from the right", actions.fetchSemanticsNode().positionInRoot.x < 0f)
        actions.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        assertCurrent("next")
        swipe(next = true)
        assertCurrent("after")
        assertStripIsOriginalOnly()
    }

    @Test fun `returning from the first recommendation restores the liked posts original next neighbor`() {
        showViewer()
        openRelated("r1")
        swipe(next = false)
        assertCurrent("seed")
        swipe(next = true)
        assertCurrent("next")
        assertStripIsOriginalOnly()
    }

    @Test fun `original thumbnails remain usable while browsing recommendations`() {
        showViewer()
        openRelated("r1")
        compose.onNode(hasAnyAncestor(hasTestTag("viewer-post-navigation-strip")) and hasContentDescription("Open post after")).performClick()
        compose.waitForIdle()
        assertCurrent("after")
        assertStripIsOriginalOnly()
    }

    private fun showViewer() {
        val initial = ViewerSession(originals, ViewerLaunchContext("query", 1, ViewerStreamSource.SEARCH, 0), sessionId = "screen-order")
        val session = mutableStateOf(initial)
        state = mutableStateOf(reduceViewerState(ViewerUiState.Empty, initial.toReplaceViewerSessionAction()).state)
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value.copy(related = ViewerRelatedUiState(
                        RelatedPostsUiState.Loaded(RelatedPostsRequest(originals[1], 1, 1), related), related,
                        session.value.relatedNavigation, session.value.relatedPostIds,
                    )),
                    creatorBrowsingSources = emptySet(),
                    onAction = { state.value = reduceViewerState(state.value, it).state },
                    onIncludeRelatedPosts = {
                        session.value = session.value.withRelatedPosts(originals[1].id, related)
                            .let { it.copy(context = it.context.copy(startIndex = state.value.currentPageIndex)) }
                        state.value = reduceViewerState(state.value, session.value.toReplaceViewerSessionAction()).state
                    },
                    onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> }, onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun openRelated(id: String) {
        compose.onNodeWithContentDescription("Show related posts").performClick()
        compose.onNodeWithContentDescription("Open post $id").performClick()
        compose.waitForIdle()
        assertCurrent(id)
    }

    private fun swipe(next: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Media actions").performTouchInput { if (next) swipeLeft() else swipeRight() }
        compose.mainClock.advanceTimeBy(400)
    }

    private fun assertCurrent(id: String) = compose.runOnIdle { assertEquals(id, state.value.currentPage?.post?.id?.sourcePostId) }

    private fun assertStripIsOriginalOnly() {
        val inStrip = hasAnyAncestor(hasTestTag("viewer-post-navigation-strip"))
        related.forEach { compose.onNode(inStrip and hasContentDescription("Open post ${it.id.sourcePostId}")).assertDoesNotExist() }
    }
}
