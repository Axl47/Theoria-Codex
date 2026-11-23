package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
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
import coil.Coil
import org.junit.After
import org.junit.Before
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

    // Pages fail at once on the main thread; on Coil's background thread they could land after idling, and a Retry
    // tapped before the Viewer holds page 1's error only reloads that page locally.
    @Before fun failImageLoads() = installFailingLoader()
    @After fun resetCoil() = Coil.reset()

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
        repeat(3) { compose.onRoot().performTouchInput { swipeUp(startY = centerY, endY = top) } }
        compose.waitForIdle()
        assertEquals(0, state.value.currentPageIndex)
        assertTrue("selected page ${state.value.currentPage?.selectedMediaIndex}", (state.value.currentPage?.selectedMediaIndex ?: 0) > 0)
    }

    @Test fun `starting a slideshow in a zoomed vertical gallery lets the next touch stop it`() {
        show()
        compose.onRoot().performTouchInput {
            pinch(Offset(centerX - width * 0.1f, centerY), Offset(centerX + width * 0.1f, centerY),
                Offset(centerX - width * 0.4f, centerY), Offset(centerX + width * 0.4f, centerY))
        }
        compose.waitForIdle()
        // Hold the clock so the slideshow cannot run to its end on its own before the touch.
        compose.mainClock.autoAdvance = false
        state.value = reduceViewerState(state.value, ViewerAction.SetSlideshow(true)).state
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(FRAME_MS * 3)
        compose.waitForIdle()
        assertTrue(state.value.controls.slideshow)
        compose.onRoot().performTouchInput { click(Offset(centerX, centerY - height / 8f)) }
        compose.mainClock.advanceTimeBy(TAP_SETTLE_MS)
        assertFalse(state.value.controls.slideshow)
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

    @Test fun `a page that fails to load in a vertical gallery is reported to the Viewer`() {
        show()
        compose.runOnIdle { assertEquals(ViewerMediaKey(gallery.id, 0), state.value.mediaError?.mediaKey) }
    }

    @Test fun `a vertical gallery resumes its saved page once its pages resolve`() {
        val session = ViewerSessionIdentity("resume")
        val sparse = gallery.copy(media = listOf(page), mediaCount = gallery.media.size)
        val recorded = mutableListOf<Int>()
        state.value = createViewerUiState(
            session, listOf(sparse, next), requiresResolution = { it.id == gallery.id }, initialMediaIndices = mapOf(gallery.id to 4),
        )
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        onVisibleMediaChanged = { post, number -> if (post.id == gallery.id) recorded += number },
                    ),
                    config = ViewerScreenConfig(galleryReadingModes = mapOf(gallery.id to GalleryReadingMode.VERTICAL)),
                )
            }
        }
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, gallery)).state }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(4, state.value.currentPage?.selectedMediaIndex)
            assertTrue("recorded $recorded", recorded.isNotEmpty() && recorded.all { it == 5 })
        }
    }

    @Test fun `opening and closing the overview keeps a vertical gallery's scroll position`() {
        show()
        repeat(2) { compose.onRoot().performTouchInput { swipeUp(startY = centerY, endY = top) } }
        compose.waitForIdle()
        val before = pagePosition()
        assertTrue("scrolled past page 1: $before", before.first > 1)
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ToggleOverview).state }
        compose.onNodeWithText("Vertical").assertIsDisplayed()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ToggleOverview).state }
        compose.waitForIdle()
        assertEquals(before, pagePosition())
    }

    /** The first page on screen and how far its top sits from the screen's top. */
    private fun pagePosition(): Pair<Int, Float> {
        val page = (1..gallery.media.size).first { runCatching { compose.onNodeWithContentDescription("Page $it").assertIsDisplayed() }.isSuccess }
        return page to compose.onNodeWithContentDescription("Page $page").fetchSemanticsNode().positionInRoot.y
    }

    @Test fun `a failed page's Retry goes through the Viewer's shared retry`() {
        show()
        compose.onNode(hasText("Retry") and hasAnyAncestor(hasContentDescription("Page 1"))).performClick()
        assertTrue(actions.any { it is ViewerAction.RetryMedia })
    }

    @Test fun `picking a page shorter than a third of the screen keeps it selected`() {
        val strips = gallery.copy(width = 3000, height = 400, media = (1..30).map { page.copy(localPath = "/missing/strip-$it.jpg") })
        state.value = createViewerUiState(ViewerSessionIdentity("short"), listOf(strips, next))
        show()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.SelectMedia(10)).state }
        compose.waitForIdle()
        assertEquals(10, state.value.currentPage?.selectedMediaIndex)
    }

    @Test fun `pages resizing as they load leave the selected page alone`() {
        // Unloaded pages borrow the post's tall shape; once loaded they are wide, which slides another page under the line.
        val land = installGatedLoader(width = 300, height = 100)
        show()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.SelectMedia(3)).state }
        compose.waitForIdle()
        actions.clear()
        compose.runOnIdle { land() }
        compose.waitForIdle()
        assertEquals(emptyList<ViewerAction>(), actions.filterIsInstance<ViewerAction.SelectMedia>())
        assertEquals(3, state.value.currentPage?.selectedMediaIndex)
    }

    @Test fun `a narrower screen pulls a zoomed pan back inside the pages`() {
        val zoomed = ViewerVerticalGalleryState(LazyListState()).apply {
            resize(2400)
            transform(zoomBy = 2f, focusX = 0f, focusY = 0f, panBy = -2400f)
        }
        assertEquals(2400f, zoomed.panX)
        zoomed.resize(1080)
        assertEquals(1080f, zoomed.panX)
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

    private companion object {
        const val FRAME_MS = 16L
        const val TAP_SETTLE_MS = 500L
    }
}
