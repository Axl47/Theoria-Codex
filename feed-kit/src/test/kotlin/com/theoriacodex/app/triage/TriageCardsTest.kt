package com.theoriacodex.app.triage

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
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

    private fun showCards(footer: (@Composable (Post) -> Unit)? = null) = compose.setContent {
        MaterialTheme {
            TriageCards(
                TriageDeckState(posts = listOf(top, next)), drag, TriageCardSupport(environment, null, null),
                SwipeThresholds(commitPx = 100f, flingPx = 100f, cuePx = 10f), ratioFloor = 0.5f, footer = footer, onOpen = {},
            )
        }
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
        showCards { post -> Text("footer ${post.id.sourcePostId}") }
        compose.onNodeWithText("footer top").assertExists()
        compose.onNodeWithText("footer next").assertDoesNotExist()

        compose.runOnIdle { drag.dragBy(Offset(20f, 0f), Offset(20f, 0f)) }
        compose.onNodeWithText("footer next").assertExists()
        assertEquals(2, compose.onAllNodesWithTag(TRIAGE_CARD_FOOTER_TAG).fetchSemanticsNodes().size)
    }
}
