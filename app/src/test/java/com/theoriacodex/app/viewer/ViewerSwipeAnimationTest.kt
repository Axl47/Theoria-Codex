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

    @Test fun `inverted gallery follows a held right drag`() = checkSlide(gallery = true, heldDrag = true, inverted = true)

    @Test fun `related thumbnail joins the session and animates to its post`() = checkSlide(gallery = false, related = true)

    @Test fun `held drag resolves destination without selecting it and cancellation preserves selection`() {
        val posts = slidePosts(gallery = false)
        val state = mutableStateOf(createViewerUiState(
            ViewerSessionIdentity("swipe"), posts, requiresResolution = { it.id == posts[1].id },
        ))
        setSlideContent(state, ViewerReturnTransition(), posts.first(), posts, related = false, inverted = false)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val actions = compose.onNodeWithContentDescription("Media actions")
        val width = actions.fetchSemanticsNode().size.width
        actions.performTouchInput {
            down(Offset(width * 0.8f, height * 0.5f))
            moveTo(Offset(width * 0.72f, height * 0.5f))
            moveTo(Offset(width * 0.72f, height * 0.5f))
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(com.theoriacodex.app.viewer.state.ViewerResolutionStatus.REQUESTED,
                state.value.pages[1].resolution.status)
        }
        actions.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(0, state.value.currentPageIndex) }
    }

    @Test fun `short drag after a completed swipe stays on the selected post`() {
        val first = slidePosts(gallery = false).first()
        val posts = listOf("first", "second", "third").map { first.copy(id = PostId(SourceKey.GELBOORU, it)) }
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("swipe"), posts, requiresResolution = { false }))
        setSlideContent(state, ViewerReturnTransition(), posts.first(), posts, related = false, inverted = false)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Media actions").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, state.value.currentPageIndex) }

        val actions = compose.onNodeWithContentDescription("Media actions")
        val width = actions.fetchSemanticsNode().size.width
        actions.performTouchInput {
            down(Offset(width * 0.8f, height * 0.5f))
            moveTo(Offset(width * 0.72f, height * 0.5f))
            moveTo(Offset(width * 0.72f, height * 0.5f))
            up()
        }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(1, state.value.currentPageIndex) }
        // Only the selected page exposes these actions, so a mismatched pager leaves no such node.
        assertEquals(0, compose.onNodeWithContentDescription("Media actions").fetchSemanticsNode().positionInRoot.x.toInt())
    }

    private fun checkSlide(gallery: Boolean, opening: Boolean = false, thumbnail: Boolean = false, heldDrag: Boolean = false, cancelled: Boolean = false, related: Boolean = false, inverted: Boolean = false) {
        val posts = slidePosts(gallery)
        val first = posts.first()
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("swipe"), if (related) posts.take(1) else posts, requiresResolution = { false }))
        val transition = ViewerReturnTransition().apply {
            if (opening) openedFrom("feed", Rect(20f, 100f, 220f, 400f))
        }
        setSlideContent(state, transition, first, posts, related, inverted)
        compose.waitForIdle()
        if (opening) compose.runOnIdle { assertTrue(state.value.controls.chromeVisible) }
        compose.mainClock.autoAdvance = false
        val actions = compose.onNodeWithContentDescription("Media actions")
        val width = actions.fetchSemanticsNode().size.width
        performSlide(actions, width, state, related, heldDrag, thumbnail, inverted, cancelled)
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(0, state.value.currentPage?.selectedMediaIndex)
        }
        val x = actions.fetchSemanticsNode().positionInRoot.x
        assertTrue("The outgoing page should be partway across the screen: x=$x", if (inverted) x > 0f && x < width else x < 0f && x > -width)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertFinalSelection(state.value, gallery, cancelled)
        }
    }

    private fun setSlideContent(
        state: androidx.compose.runtime.MutableState<com.theoriacodex.app.viewer.state.ViewerUiState>,
        transition: ViewerReturnTransition,
        first: Post,
        posts: List<Post>,
        related: Boolean,
        inverted: Boolean,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                MaterialTheme {
                    ViewerScreen(
                        uiState = state.value.copy(related = relatedState(first, posts, related)), creatorBrowsingSources = emptySet(),
                        invertMultiImageScrollDirection = inverted,
                        onAction = { state.value = reduceViewerState(state.value, it).state },
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
    }


    private fun relatedState(first: Post, posts: List<Post>, related: Boolean) =
        com.theoriacodex.app.viewer.state.ViewerRelatedUiState(
                                    if (related) com.theoriacodex.app.related.RelatedPostsUiState.Loaded(
                                        com.theoriacodex.app.related.RelatedPostsRequest(first, 0, 1), posts.drop(1))
                                        else com.theoriacodex.app.related.RelatedPostsUiState.Idle,
                                    if (related) posts.drop(1) else emptyList(),
                                    if (related) com.theoriacodex.app.viewer.state.ViewerRelatedNavigation(first.id, posts.drop(1).map { it.id }) else null,
                                    if (related) posts.drop(1).mapTo(mutableSetOf()) { it.id } else emptySet(),
                                )


    private fun performHeldDrag(
        actions: androidx.compose.ui.test.SemanticsNodeInteraction,
        width: Int,
        state: androidx.compose.runtime.State<com.theoriacodex.app.viewer.state.ViewerUiState>,
        inverted: Boolean,
        cancelled: Boolean,
    ) {
        actions.performTouchInput {
            down(Offset(width * if (inverted) 0.2f else 0.8f, height * 0.5f))
            moveTo(Offset(width * dragEndFraction(inverted, cancelled), height * 0.5f))
            moveTo(Offset(width * dragEndFraction(inverted, cancelled), height * 0.5f))
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(0, state.value.currentPage?.selectedMediaIndex)
        }
        val dragX = actions.fetchSemanticsNode().positionInRoot.x
        assertTrue("Content must follow the drag before release: $dragX", if (inverted) dragX > 0f else dragX < 0f)
        actions.performTouchInput { up() }
    }


    private fun dragEndFraction(inverted: Boolean, cancelled: Boolean): Float = when {
        inverted -> 0.45f
        cancelled -> 0.72f
        else -> 0.55f
    }

    private fun slidePosts(gallery: Boolean): List<Post> {
        val image = ImageRef(url = null, localPath = "/missing/swipe.jpg", mime = "image/jpeg")
        val first = Post(
            id = PostId(SourceKey.GELBOORU, "first"), preview = image, full = image,
            media = if (gallery) listOf(image, image.copy(localPath = "/missing/second.jpg")) else listOf(image),
            pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null,
        )
        return if (gallery) listOf(first) else listOf(first, first.copy(id = PostId(SourceKey.GELBOORU, "second")))
    }

    private fun performSlide(
        actions: androidx.compose.ui.test.SemanticsNodeInteraction,
        width: Int,
        state: androidx.compose.runtime.State<com.theoriacodex.app.viewer.state.ViewerUiState>,
        related: Boolean,
        heldDrag: Boolean,
        thumbnail: Boolean,
        inverted: Boolean,
        cancelled: Boolean,
    ) {
        if (related) {
            compose.onNodeWithContentDescription("Show related posts").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithContentDescription("Open post second").performClick()
        } else if (heldDrag) {
            performHeldDrag(actions, width, state, inverted, cancelled)
        } else if (thumbnail) compose.onNodeWithContentDescription("Open post second").performClick()
        else actions.performTouchInput { swipeLeft() }
    }

    private fun assertFinalSelection(
        state: com.theoriacodex.app.viewer.state.ViewerUiState,
        gallery: Boolean,
        cancelled: Boolean,
    ) {
        assertEquals(if (gallery || cancelled) 0 else 1, state.currentPageIndex)
        assertEquals(if (gallery && !cancelled) 1 else 0, state.currentPage?.selectedMediaIndex)
    }

}
