package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w800dp-h600dp-mdpi")
class ViewerPostStripTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `compact strip centers its selected post with equal inner and edge gaps`() = checkSpacing(320.dp, slots = 7)
    @Test fun `wide strip fits more slots and spreads them evenly across the viewport`() = checkSpacing(720.dp, slots = 11)

    @Test fun `strip scrolls beyond the seven visible slots selects distant posts and requests more`() {
        val posts = (0..39).map { testPost(sourcePostId = "strip-$it") }
        var selected = -1
        var loadRequests = 0
        compose.setContent {
            MaterialTheme {
                ViewerPostStrip(posts, 0, { selected = it }, Modifier.width(320.dp), onLoadMore = { loadRequests++ })
            }
        }
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performTouchInput { swipeLeft(durationMillis = 500) }
        compose.onNodeWithContentDescription("Open post strip-0").assertIsNotDisplayed()
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(36)
        compose.onNodeWithContentDescription("Open post strip-36").performClick()
        compose.runOnIdle {
            assertEquals(36, selected)
            org.junit.Assert.assertTrue(loadRequests > 0)
        }
    }

    @Test fun `only animated posts show the play icon`() {
        val video = testPost(sourcePostId = "video").copy(preview = ImageRef(null, "/missing/clip.mp4", "video/mp4"))
        val still = testPost(sourcePostId = "still").copy(preview = ImageRef(null, "/missing/still.jpg", "image/jpeg"))
        compose.setContent {
            MaterialTheme { ViewerPostStrip(listOf(video, still), 0, {}, Modifier.width(320.dp)) }
        }
        compose.onNodeWithContentDescription("Open post video").assertContentDescriptionContains("Animated")
        compose.onNodeWithContentDescription("Open post still").assert(!hasContentDescription("Animated"))
    }

    @Test fun `a long open post scrolls its pages between the neighbouring posts`() {
        val selectedPages = mutableListOf<Int>()
        setUnfolded(pageCount = 12, currentPage = 6) { selectedPages += it }
        val previous = compose.onNodeWithContentDescription("Open post strip-2").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription("Open post strip-4").fetchSemanticsNode().boundsInRoot
        val current = compose.onNodeWithContentDescription("Go to page 7 of 12").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("strip").fetchSemanticsNode().boundsInRoot
        assertTrue("previous post starts at ${previous.left}", previous.left >= viewport.left)
        assertTrue("next post ends at ${next.right}", next.right <= viewport.right)
        assertEquals(viewport.center.x, current.center.x, 1.5f)
        compose.onNodeWithContentDescription("Go to page 1 of 12").assertDoesNotExist()
        compose.onNodeWithContentDescription("Go to page 8 of 12").performClick()
        compose.runOnIdle { assertEquals(listOf(7), selectedPages) }
    }

    @Test fun `the first and last pages of a long post scroll fully into view`() {
        var page by mutableStateOf(0)
        setUnfolded(pageCount = 12, currentPage = { page })
        val previous = compose.onNodeWithContentDescription("Open post strip-2").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithContentDescription("Go to page 1 of 12").fetchSemanticsNode().boundsInRoot
        assertTrue("page 1 starts at ${first.left}", first.left > previous.right)
        compose.runOnIdle { page = 11 }
        compose.waitForIdle()
        val next = compose.onNodeWithContentDescription("Open post strip-4").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithContentDescription("Go to page 12 of 12").fetchSemanticsNode().boundsInRoot
        assertTrue("page 12 ends at ${last.right}", last.right < next.left)
    }

    @Test fun `a short open post takes only the room its pages need`() {
        setUnfolded(pageCount = 3, currentPage = 1)
        val pages = (1..3).map { compose.onNodeWithContentDescription("Go to page $it of 3").fetchSemanticsNode().boundsInRoot }
        val viewport = compose.onNodeWithTag("strip").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.x, pages[1].center.x, 1.5f)
        compose.onNodeWithContentDescription("Open post strip-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open post strip-5").assertIsDisplayed()
    }

    @Test fun `pages only unfold for the post the strip has selected`() {
        val posts = (0..6).map { testPost(sourcePostId = "strip-$it") }
        compose.setContent {
            MaterialTheme {
                ViewerPostStrip(posts, 3, {}, Modifier.width(360.dp), openPages = stripPages(posts[0].id, 4, 0))
            }
        }
        compose.onNodeWithContentDescription("Open post strip-3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go to page 1 of 4").assertDoesNotExist()
    }

    @Test fun `only multi-page posts announce their page count`() {
        val posts = listOf(testPost(sourcePostId = "single").copy(mediaCount = 1), testPost(sourcePostId = "gallery").copy(mediaCount = 8))
        compose.setContent { MaterialTheme { ViewerPostStrip(posts, 0, {}, Modifier.width(360.dp)) } }
        compose.onNodeWithContentDescription("Open post gallery").assertStateDescription("8 pages")
        compose.onNodeWithContentDescription("Open post single").assert(!hasStateDescription("8 pages"))
    }

    @Test fun `changing post moves the row smoothly, even when a second change interrupts the first`() {
        var selected by mutableStateOf(3)
        val image = ImageRef(null, "/missing/strip.jpg", "image/jpeg")
        val posts = (0..9).map { testPost(sourcePostId = "strip-$it").copy(preview = image) }
        val pages = stripPages(posts[3].id, 3, 0)
        compose.setContent {
            MaterialTheme { ViewerPostStrip(posts, selected, {}, Modifier.width(360.dp).testTag("strip"), openPages = pages) }
        }
        compose.waitForIdle()
        fun tracked() = compose.onNodeWithContentDescription("Open post strip-5").fetchSemanticsNode().boundsInRoot.center.x
        compose.mainClock.autoAdvance = false

        var last = tracked()
        fun advance(frames: Int) = repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            val now = tracked()
            assertTrue("the row jumped ${now - last}px in one frame", kotlin.math.abs(now - last) < 23f)
            last = now
        }
        compose.runOnUiThread { selected = 4 }
        advance(5)
        compose.runOnUiThread { selected = 3 }
        advance(30)

        val current = compose.onNodeWithContentDescription("Go to page 2 of 3").fetchSemanticsNode().boundsInRoot
        assertTrue("the post is open and its pages are on screen", current.width > 0f)
    }

    private fun setUnfolded(pageCount: Int, currentPage: Int, onSelect: (Int) -> Unit = {}) = setUnfolded(pageCount, { currentPage }, onSelect)

    private fun setUnfolded(pageCount: Int, currentPage: () -> Int, onSelect: (Int) -> Unit = {}) {
        val image = ImageRef(null, "/missing/strip.jpg", "image/jpeg")
        val posts = (0..6).map { testPost(sourcePostId = "strip-$it").copy(preview = image) }
        compose.setContent {
            MaterialTheme {
                ViewerPostStrip(posts, 3, {}, Modifier.width(360.dp).testTag("strip"), openPages = stripPages(posts[3].id, pageCount, currentPage(), onSelect))
            }
        }
        compose.waitForIdle()
    }

    private fun stripPages(postId: PostId, pageCount: Int, currentPage: Int, onSelect: (Int) -> Unit = {}): ViewerStripPages {
        val items = List(pageCount) { index ->
            ViewerMediaOverviewItem(index, ImageRef(null, "/missing/$index.jpg", "image/jpeg"), ViewerMediaOverviewKind.STILL_IMAGE, "/missing/$index.jpg")
        }
        return ViewerStripPages(postId, items, currentPage, reversed = false, onSelect)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertStateDescription(expected: String) = assert(hasStateDescription(expected))

    private fun hasStateDescription(expected: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    private fun checkSpacing(width: Dp, slots: Int) {
        val image = ImageRef(null, "/missing/strip.jpg", "image/jpeg")
        val posts = (0 until slots).map { testPost(sourcePostId = "strip-$it").copy(preview = image) }
        compose.setContent {
            MaterialTheme { ViewerPostStrip(posts, slots / 2, {}, Modifier.width(width).testTag("strip")) }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("strip").fetchSemanticsNode().boundsInRoot
        val cells = posts.map { compose.onNodeWithContentDescription("Open post ${it.id.sourcePostId}").fetchSemanticsNode().boundsInRoot }
        assertEquals(viewport.center.x, cells[slots / 2].center.x, 1f)
        val edgeGap = cells.first().left - viewport.left
        cells.zipWithNext().forEach { (left, right) -> assertEquals(edgeGap, right.left - left.right, 1.5f) }
        assertEquals(edgeGap, viewport.right - cells.last().right, 1.5f)
    }
}
