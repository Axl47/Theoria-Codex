package com.theoriacodex.app.triage

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TriageCardsTest {
    @get:Rule val compose = createComposeRule()

    private val top = testPost(sourcePostId = "top")
    private val next = testPost(sourcePostId = "next")
    private val visible = mutableSetOf<PostId>()
    private val environment = FeedCardEnvironment(
        null, mutableStateOf(emptySet()), mutableStateOf(emptyMap()), noMediaDurationStateForPost, {},
        { post, shown -> if (shown) visible += post.id else visible -= post.id }, { _, _ -> },
    )
    private val drag = TriageDrag()

    private val viewerReturn = ViewerReturnTransition()

    private fun showCards(commitPx: Float = 100f, footer: (@Composable (Post) -> Unit)? = null) = compose.setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalViewerReturnTransition provides viewerReturn) {
                Box(Modifier.fillMaxSize().background(Color.Red)) {
                    TriageCards(
                        TriageDeckState(posts = listOf(top, next)), drag, TriageCardSupport(environment, null, null),
                        SwipeThresholds(commitPx = commitPx, flingPx = 100f, cuePx = 10f), ratioFloor = 0.5f, footer = footer, onOpen = {},
                    )
                }
            }
        }
    }

    private fun cardShowingAt(x: Int, y: Int): Float = compose.onRoot().captureToImage().toPixelMap()[x, y].let { maxOf(1f - it.red, it.green, it.blue) }

    @Test
    fun `the next card shows only as far as the top card has travelled`() {
        showCards(commitPx = 4_000f)
        val behindTopCardEdge = 30
        val middle = 400
        compose.runOnIdle { drag.dragBy(Offset(200f, 0f), Offset(200f, 0f)) }
        assertTrue("a short drag leaves the next card nearly unseen", cardShowingAt(behindTopCardEdge, middle) < 0.1f)

        compose.runOnIdle { drag.dragBy(Offset(3_800f, 0f), Offset(4_000f, 0f)) }
        assertTrue("a full drag shows the next card", cardShowingAt(behindTopCardEdge, middle) > 0.3f)
    }

    @Test
    fun `dragging redraws the cards without recomposing them`() {
        var compositions = 0
        showCards { compositions++ }
        compose.runOnIdle { drag.dragBy(Offset(5f, 0f), Offset(5f, 0f)) }
        compose.waitForIdle()
        val afterStart = compositions
        repeat(20) { step -> compose.runOnIdle { drag.dragBy(Offset(3f, 0f), Offset(8f + step * 3f, 0f)) } }
        compose.waitForIdle()
        assertEquals(afterStart, compositions)
    }

    @Test
    fun `the next card is a visible feed card while the top card is dragged and gone at rest`() {
        showCards()
        compose.runOnIdle { assertEquals(setOf(top.id), visible) }

        compose.runOnIdle { drag.dragBy(Offset(20f, 0f), Offset(20f, 0f)) }
        compose.runOnIdle { assertEquals(setOf(top.id, next.id), visible) }

        compose.runOnIdle { drag.dragBy(Offset(-20f, 0f), Offset.Zero) }
        compose.runOnIdle { assertEquals(setOf(top.id), visible) }
    }

    @Test
    fun `each shown card carries its own footer`() {
        showCards(footer = { post -> Text("footer ${post.id.sourcePostId}") })
        compose.onNodeWithText("footer top").assertExists()
        compose.onNodeWithText("footer next").assertDoesNotExist()

        compose.runOnIdle { drag.dragBy(Offset(20f, 0f), Offset(20f, 0f)) }
        compose.onNodeWithText("footer next").assertExists()
        assertEquals(2, compose.onAllNodesWithTag(TRIAGE_CARD_FOOTER_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun `a Viewer opened from the top card closes into that card (ADR 0220)`() {
        showCards()
        compose.onNodeWithTag(searchCardTestTag(top.id)).performClick()
        compose.runOnIdle { viewerReturn.begin(top.id) }
        compose.waitForIdle()

        val card = compose.onNodeWithTag(searchCardTestTag(top.id)).fetchSemanticsNode().boundsInWindow
        val target = checkNotNull(viewerReturn.targetBounds) { "The card never answered the return" }
        assertTrue("The return lands in $target, outside the card $card", card.contains(target.center))
    }
}
