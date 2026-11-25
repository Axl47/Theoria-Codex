package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import com.theoriacodex.app.viewer.state.ViewerPanel
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

    @Before fun failImageLoads() { installFailingLoader() }
    @After fun resetCoil() = Coil.reset()

    private fun show(
        modes: Map<PostId, GalleryReadingMode> = mapOf(gallery.id to GalleryReadingMode.VERTICAL),
        returning: ViewerReturnTransition? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides returning) {
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
        }
        compose.waitForIdle()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a vertical gallery closing into its card is gone once the backdrop is (ADR 0218)`() {
        val returning = ViewerReturnTransition()
        returning.openedFrom("grid", post = gallery.id)
        returning.begin(gallery.id)
        returning.recordBounds("grid", gallery.id, Rect(0f, 0f, 40f, 50f))
        returning.positioned("grid", gallery.id)
        show(returning = returning)
        val page = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode().boundsInRoot
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val left = page.left.toInt().coerceAtLeast(0)
        val top = page.top.toInt().coerceAtLeast(0)
        val right = page.right.toInt().coerceAtMost(pixels.width) - 1
        val bottom = page.bottom.toInt().coerceAtMost(pixels.height) - 1
        val drawn = (left..right step 4).flatMap { x -> (top..bottom step 4).map { y -> pixels[x, y] } }.distinct()
        assertEquals("The pages are still drawn after the return landed: $drawn", 1, drawn.size)
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

    @Test fun `a sideways swipe in a vertical gallery moves to the next post (ADR 0168)`() {
        show()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.value.currentPageIndex)
    }

    @Test fun `pulling down at the top of a vertical gallery closes the Viewer (ADR 0168)`() {
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
                        onVisibleMediaChanged = { post, number, _ -> if (post.id == gallery.id) recorded += number },
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

    @Test fun `a vertical gallery opened at its first page reports it again once its pages resolve`() {
        val session = ViewerSessionIdentity("first")
        val sparse = gallery.copy(media = listOf(page), mediaCount = gallery.media.size)
        val reported = mutableListOf<Int>()
        state.value = createViewerUiState(session, listOf(sparse, next), requiresResolution = { it.id == gallery.id })
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        onVisibleMediaChanged = { post, _, _ -> if (post.id == gallery.id) reported += post.media.size },
                    ),
                    config = ViewerScreenConfig(galleryReadingModes = mapOf(gallery.id to GalleryReadingMode.VERTICAL)),
                )
            }
        }
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, gallery)).state }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("reported page counts $reported", gallery.media.size, reported.lastOrNull()) }
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a chapter reopens where the reader left it inside its page (ADR 0192)`() {
        val land = installGatedLoader(width = 100, height = 400)
        state.value = createViewerUiState(
            ViewerSessionIdentity("offset"), listOf(gallery, next), initialMediaIndices = mapOf(gallery.id to 2),
            series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL, pageFractions = mapOf(gallery.id to ViewerPageFraction(2, 0.5f))),
        )
        show()
        compose.runOnIdle { land() }
        compose.waitForIdle()

        val page = compose.onNodeWithContentDescription("Page 3").fetchSemanticsNode()
        val line = compose.onRoot().fetchSemanticsNode().size.height / 3f
        assertEquals(0.5f, (line - page.positionInRoot.y) / page.size.height, 0.02f)
        assertEquals(2, state.value.currentPage?.selectedMediaIndex)
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a chapter whose pages arrive after it opens still reopens inside its page (ADR 0192)`() {
        val land = installGatedLoader(width = 100, height = 400)
        val session = ViewerSessionIdentity("arriving")
        state.value = createViewerUiState(
            session, listOf(gallery.copy(media = listOf(page)), next), requiresResolution = { it.id == gallery.id },
            initialMediaIndices = mapOf(gallery.id to 2),
            series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL, pageFractions = mapOf(gallery.id to ViewerPageFraction(2, 0.5f))),
        )
        show()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, gallery)).state }
        compose.waitForIdle()
        compose.runOnIdle { land() }
        compose.waitForIdle()

        val shown = compose.onNodeWithContentDescription("Page 3").fetchSemanticsNode()
        val line = compose.onRoot().fetchSemanticsNode().size.height / 3f
        assertEquals(0.5f, (line - shown.positionInRoot.y) / shown.size.height, 0.02f)
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a chapter left on its first page restores onto that page, not onto the cover shown while it loads (ADR 0192)`() {
        val land = installGatedLoader(width = 100, height = 400)
        val session = ViewerSessionIdentity("first-page")
        state.value = createViewerUiState(
            session, listOf(gallery.copy(media = emptyList(), full = null, galleryLengthUnknown = true), next),
            requiresResolution = { it.id == gallery.id },
            series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL, pageFractions = mapOf(gallery.id to ViewerPageFraction(0, 0.5f))),
        )
        show()
        compose.runOnIdle { land() }
        compose.mainClock.advanceTimeBy(FRAME_MS * 10)
        compose.waitForIdle()
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, gallery)).state }
        compose.mainClock.advanceTimeBy(FRAME_MS * 10)
        compose.waitForIdle()

        val shown = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode()
        val line = compose.onRoot().fetchSemanticsNode().size.height / 3f
        assertEquals(0.5f, (line - shown.positionInRoot.y) / shown.size.height, 0.02f)
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a chapter left near the top of its page reopens with that page first on screen (ADR 0192)`() {
        val land = installGatedLoader(width = 100, height = 400)
        state.value = createViewerUiState(
            ViewerSessionIdentity("near-top"), listOf(gallery, next), initialMediaIndices = mapOf(gallery.id to 2),
            series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL, pageFractions = mapOf(gallery.id to ViewerPageFraction(2, 0.05f))),
        )
        show()
        compose.runOnIdle { land() }
        compose.waitForIdle()

        assertEquals(0f, compose.onNodeWithContentDescription("Page 3").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a saved fraction for another page is dropped and the page shown reports at once (ADR 0192)`() {
        val reported = mutableListOf<Pair<Int, Float?>>()
        state.value = createViewerUiState(
            ViewerSessionIdentity("other-page"), listOf(gallery, next), initialMediaIndices = mapOf(gallery.id to 3),
            series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL, pageFractions = mapOf(gallery.id to ViewerPageFraction(2, 0.5f))),
        )
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        onVisibleMediaChanged = { post, number, offset -> if (post.id == gallery.id) reported += number to offset },
                    ),
                    config = ViewerScreenConfig(galleryReadingModes = mapOf(gallery.id to GalleryReadingMode.VERTICAL)),
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(Offset(centerX, centerY))
            repeat(12) { moveBy(Offset(0f, -height / 10f)) }
        }
        compose.waitForIdle()

        assertTrue("reported $reported", reported.first() == (4 to null) && reported.any { it.first == 5 })
        compose.onRoot().performTouchInput { up() }
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `scrolling inside a chapter's page reports how far into the page the reader is (ADR 0192)`() {
        val land = installGatedLoader(width = 100, height = 400)
        val reported = mutableListOf<Pair<Int, Float?>>()
        state.value = createViewerUiState(
            ViewerSessionIdentity("report"), listOf(gallery, next), series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL),
        )
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value,
                    host = ViewerScreenHost(
                        onAction = { state.value = reduceViewerState(state.value, it).state },
                        onVisibleMediaChanged = { post, number, offset -> if (post.id == gallery.id) reported += number to offset },
                    ),
                    config = ViewerScreenConfig(galleryReadingModes = mapOf(gallery.id to GalleryReadingMode.VERTICAL)),
                )
            }
        }
        compose.runOnIdle { land() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + height / 8f, endY = centerY - height / 8f) }
        compose.waitForIdle()

        val (number, offset) = reported.last()
        assertEquals(1, number)
        assertEquals("measured before the page painted", null, reported.first().second)
        assertTrue("reported $reported", offset != null && offset > TOP_OF_PAGE && offset < 1f)
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a push up on a chapter still loading its pages stays on the chapter and shows it loading (ADR 0168)`() {
        val session = ViewerSessionIdentity("loading")
        val cover = gallery.copy(media = emptyList(), full = null, galleryLengthUnknown = true)
        state.value = createViewerUiState(
            session, listOf(cover, next), requiresResolution = { it.id == gallery.id }, series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL),
        )
        show(mapOf(gallery.id to GalleryReadingMode.VERTICAL, next.id to GalleryReadingMode.VERTICAL))
        compose.onNodeWithContentDescription("Loading pages").assertIsDisplayed()
        compose.onNodeWithText("Next").assertDoesNotExist()

        compose.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f) }
        compose.waitForIdle()

        assertEquals(0, state.value.currentPageIndex)
        assertEquals(null, state.value.controls.panel)
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, gallery)).state }
        compose.onNodeWithContentDescription("Loading pages").assertDoesNotExist()
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a chapter whose pages failed offers Next again, and a loading one still closes on a pull down (ADR 0168)`() {
        val session = ViewerSessionIdentity("failing")
        state.value = createViewerUiState(
            session, listOf(gallery.copy(media = emptyList(), full = null, galleryLengthUnknown = true), next),
            requiresResolution = { it.id == gallery.id }, series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL),
        )
        show(mapOf(gallery.id to GalleryReadingMode.VERTICAL, next.id to GalleryReadingMode.VERTICAL))

        compose.onRoot().performTouchInput { swipeDown(startY = centerY - height / 4f, endY = centerY + height / 4f) }
        compose.waitForIdle()
        assertTrue(actions.any { it is ViewerAction.PrepareDismiss })

        compose.runOnIdle {
            state.value = reduceViewerState(state.value, ViewerAction.ResolutionFailed(session, gallery.id, "offline", recoverable = true)).state
        }
        compose.onNodeWithText("Next").assertExists()
        compose.onNodeWithContentDescription("Loading pages").assertDoesNotExist()
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `a push up that began on a loading chapter stays on it when its pages arrive mid-swipe (ADR 0168)`() {
        val session = ViewerSessionIdentity("arriving")
        state.value = createViewerUiState(
            session, listOf(gallery.copy(media = emptyList(), full = null, galleryLengthUnknown = true), next), requiresResolution = { it.id == gallery.id },
            series = ViewerSeries.Reading(GalleryReadingMode.VERTICAL),
        )
        show(mapOf(gallery.id to GalleryReadingMode.VERTICAL, next.id to GalleryReadingMode.VERTICAL))

        compose.onRoot().performTouchInput {
            down(Offset(centerX, centerY + height / 4f))
            repeat(10) { moveBy(Offset(0f, -height / 20f)) }
        }
        compose.runOnIdle { state.value = reduceViewerState(state.value, ViewerAction.ResolutionCompleted(session, gallery)).state }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(0, state.value.currentPageIndex)
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

    private fun pagePosition(): Pair<Int, Float> {
        val page = (1..gallery.media.size).first { runCatching { compose.onNodeWithContentDescription("Page $it").assertIsDisplayed() }.isSuccess }
        return page to compose.onNodeWithContentDescription("Page $page").fetchSemanticsNode().positionInRoot.y
    }

    @Test fun `a failed page's Retry goes through the Viewer's shared retry`() {
        show()
        compose.onNode(hasText("Retry") and hasAnyAncestor(hasContentDescription("Page 1"))).performClick()
        assertTrue(actions.any { it is ViewerAction.RetryMedia })
    }

    @Test fun `a failed page taller than the screen shows its Retry on screen`() {
        val strips = gallery.copy(width = 690, height = 16_000)
        state.value = createViewerUiState(ViewerSessionIdentity("tall"), listOf(strips, next))
        show()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val retry = compose.onNode(hasText("Retry") and hasAnyAncestor(hasContentDescription("Page 1"))).getUnclippedBoundsInRoot()
        assertTrue("Retry at ${retry.top}..${retry.bottom} on a screen to ${screen.bottom}", retry.top >= screen.top && retry.bottom <= screen.bottom)
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

    @Test fun `the selected page offers TalkBack the gallery's zoom and information`() {
        show()
        fun zoomLabels() = compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }.filter { "zoom" in it.lowercase() }
        assertEquals(listOf("Zoom in"), zoomLabels())
        compose.onNodeWithContentDescription("Page 1").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Zoom in" }.action()
        compose.waitForIdle()
        assertEquals(listOf("Reset zoom"), zoomLabels())
        compose.onNodeWithContentDescription("Page 1").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(ViewerAction.ShowPanel(ViewerPanel.INFO) in actions)
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

private const val TOP_OF_PAGE = 0.19f
