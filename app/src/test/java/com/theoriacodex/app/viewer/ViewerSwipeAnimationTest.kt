package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerSwipeAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `post selection waits for a visible slide`() = checkSlide(gallery = false)
    @Test fun `gallery selection waits for a visible slide`() = checkSlide(gallery = true)

    @Test fun `opening preserves controls and allows swiping after expansion`() = checkSlide(gallery = false, opening = true)

    @Test fun `thumbnail selection slides the content before committing selection`() = checkSlide(gallery = false, thumbnail = true)

    @Test fun `post follows a held drag before release`() = checkSlide(gallery = false, heldDrag = true)
    @Test fun `gallery follows a held drag before release`() = checkSlide(gallery = true, heldDrag = true)
    @Test fun `short post drag returns to the current page`() = checkSlide(gallery = false, heldDrag = true, cancelled = true)

    @Test fun `related thumbnail joins the session and animates to its post`() = checkSlide(gallery = false, related = true)

    private fun checkSlide(gallery: Boolean, opening: Boolean = false, thumbnail: Boolean = false, heldDrag: Boolean = false, cancelled: Boolean = false, related: Boolean = false) {
        val image = ImageRef(url = null, localPath = "/missing/swipe.jpg", mime = "image/jpeg")
        val first = Post(
            id = PostId(SourceKey.GELBOORU, "first"), preview = image, full = image,
            media = if (gallery) listOf(image, image.copy(localPath = "/missing/second.jpg")) else listOf(image),
            pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null,
        )
        val posts = if (gallery) listOf(first) else listOf(first, first.copy(id = PostId(SourceKey.GELBOORU, "second")))
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("swipe"), if (related) posts.take(1) else posts, requiresResolution = { false }))
        val transition = ViewerReturnTransition().apply {
            if (opening) openedFrom("feed", Rect(20f, 100f, 220f, 400f))
        }
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                MaterialTheme {
                    ViewerScreen(
                        uiState = state.value, creatorBrowsingSources = emptySet(),
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        relatedState = if (related) com.theoriacodex.app.related.RelatedPostsUiState.Loaded(
                            com.theoriacodex.app.related.RelatedPostsRequest(first, 0, 1), posts.drop(1))
                            else com.theoriacodex.app.related.RelatedPostsUiState.Idle,
                        relatedPosts = if (related) posts.drop(1) else emptyList(),
                        onIncludeRelatedPosts = {
                            state.value = reduceViewerState(state.value,
                                com.theoriacodex.app.viewer.state.ViewerAction.ReplaceSession(ViewerSessionIdentity("swipe"), posts)).state
                        },
                        onOpenInBrowser = {}, onRemoveIncludeTerm = { _, _ -> },
                        onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        if (opening) compose.runOnIdle { assertTrue(state.value.controls.chromeVisible) }
        compose.mainClock.autoAdvance = false
        val actions = compose.onNodeWithContentDescription("Media actions")
        val width = actions.fetchSemanticsNode().size.width
        if (related) {
            compose.onNodeWithContentDescription("Show related posts").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithContentDescription("Open post second").performClick()
        } else if (heldDrag) {
            actions.performTouchInput {
                down(Offset(width * 0.8f, height * 0.5f))
                moveTo(Offset(width * if (cancelled) 0.72f else 0.55f, height * 0.5f))
                moveTo(Offset(width * if (cancelled) 0.72f else 0.55f, height * 0.5f))
            }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle {
                assertEquals(0, state.value.currentPageIndex)
                assertEquals(0, state.value.currentPage?.selectedMediaIndex)
            }
            assertTrue("Content must move before release", actions.fetchSemanticsNode().positionInRoot.x < 0f)
            actions.performTouchInput { up() }
        } else if (thumbnail) compose.onNodeWithContentDescription("Open post second").performClick()
        else actions.performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(0, state.value.currentPage?.selectedMediaIndex)
        }
        val x = actions.fetchSemanticsNode().positionInRoot.x
        assertTrue("The outgoing page should be partway across the screen: x=$x", x < 0f && x > -width)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(if (gallery || cancelled) 0 else 1, state.value.currentPageIndex)
            assertEquals(if (gallery && !cancelled) 1 else 0, state.value.currentPage?.selectedMediaIndex)
        }
    }
}
