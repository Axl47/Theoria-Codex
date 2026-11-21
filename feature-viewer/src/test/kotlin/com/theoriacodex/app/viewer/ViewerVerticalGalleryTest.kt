package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.createViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.domain.model.GalleryReadingMode
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
class ViewerVerticalGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val page = ImageRef(url = null, localPath = "/missing/page.jpg", mime = "image/jpeg")
    private val gallery = Post(
        id = PostId(SourceKey.NHENTAI, "gallery"), preview = page, full = page,
        media = (1..6).map { page.copy(localPath = "/missing/page-$it.jpg") },
        pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
    private val next = gallery.copy(id = PostId(SourceKey.NHENTAI, "next"), media = listOf(page))
    private val actions = mutableListOf<ViewerAction>()
    private val state = mutableStateOf(createViewerUiState(ViewerSessionIdentity("vertical"), listOf(gallery, next)))

    private fun show(modes: Map<PostId, GalleryReadingMode> = mapOf(gallery.id to GalleryReadingMode.VERTICAL)) {
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(onAction = { action ->
                        actions += action
                        state.value = reduceViewerState(state.value, action).state
                    }),
                    config = ViewerScreenConfig(galleryReadingModes = modes),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun `scrolling a vertical gallery selects the page in view`() {
        show()
        compose.onNodeWithContentDescription("Page 1").assertIsDisplayed()
        repeat(3) { compose.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f) } }
        compose.waitForIdle()
        assertEquals(0, state.value.currentPageIndex)
        assertTrue("selected page ${state.value.currentPage?.selectedMediaIndex}", (state.value.currentPage?.selectedMediaIndex ?: 0) > 0)
    }

    @Test fun `a sideways swipe in a vertical gallery moves to the next post`() {
        show()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.value.currentPageIndex)
    }

    @Test fun `pulling down at the top of a vertical gallery closes the Viewer`() {
        show()
        compose.onRoot().performTouchInput { swipeDown(startY = centerY - height / 4f, endY = centerY + height / 4f) }
        compose.waitForIdle()
        assertTrue(actions.any { it is ViewerAction.PrepareDismiss })
    }

    @Test fun `the overview switches a gallery's reading mode`() {
        val chosen = mutableListOf<Pair<PostId, GalleryReadingMode>>()
        state.value = reduceViewerState(state.value, ViewerAction.ToggleOverview).state
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        onGalleryReadingModeSelected = { id, mode -> chosen += id to mode },
                    ),
                )
            }
        }
        compose.onNodeWithText("Left to right").assertIsDisplayed()
        compose.onNodeWithText("Right to left").assertIsDisplayed()
        compose.onNodeWithText("Vertical").performClick()
        assertEquals(listOf(gallery.id to GalleryReadingMode.VERTICAL), chosen)
    }

    @Test fun `a gallery with animated pages offers no vertical mode`() {
        val animated = gallery.copy(media = listOf(page, page.copy(localPath = "/missing/clip.mp4", mime = "video/mp4")))
        state.value = reduceViewerState(
            createViewerUiState(ViewerSessionIdentity("animated"), listOf(animated)), ViewerAction.ToggleOverview,
        ).state
        compose.setContent {
            MaterialTheme { ViewerScreen(uiState = state.value, host = ViewerScreenHost(onAction = {})) }
        }
        compose.onNodeWithText("Right to left").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Vertical")).fetchSemanticsNodes().size)
    }
}
