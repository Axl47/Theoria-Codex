package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
@Config(application = Application::class, sdk = [35])
class ViewerSwipeAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `post selection waits for a visible slide`() = checkSlide(gallery = false)
    @Test fun `gallery selection waits for a visible slide`() = checkSlide(gallery = true)

    @Test fun `opening preserves controls and allows swiping after expansion`() = checkSlide(gallery = false, opening = true)

    private fun checkSlide(gallery: Boolean, opening: Boolean = false) {
        val image = ImageRef(url = null, localPath = "/missing/swipe.jpg", mime = "image/jpeg")
        val first = Post(
            id = PostId(SourceKey.GELBOORU, "first"), preview = image, full = image,
            media = if (gallery) listOf(image, image.copy(localPath = "/missing/second.jpg")) else listOf(image),
            pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
            authorName = null, createdAtEpochMs = null,
        )
        val posts = if (gallery) listOf(first) else listOf(first, first.copy(id = PostId(SourceKey.GELBOORU, "second")))
        val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("swipe"), posts, requiresResolution = { false }))
        val transition = ViewerReturnTransition().apply {
            if (opening) openedFrom("feed", Rect(20f, 100f, 220f, 400f))
        }
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                MaterialTheme {
                    ViewerScreen(
                        uiState = state.value, creatorBrowsingSources = emptySet(),
                        onAction = { state.value = reduceViewerState(state.value, it).state },
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
        actions.performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertEquals(0, state.value.currentPageIndex)
            assertEquals(0, state.value.currentPage?.selectedMediaIndex)
        }
        val x = actions.fetchSemanticsNode().positionInRoot.x
        assertTrue("The outgoing page should be partway across the screen: x=$x", x < 0f && x > -width)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(if (gallery) 0 else 1, state.value.currentPageIndex)
            assertEquals(if (gallery) 1 else 0, state.value.currentPage?.selectedMediaIndex)
        }
    }
}
