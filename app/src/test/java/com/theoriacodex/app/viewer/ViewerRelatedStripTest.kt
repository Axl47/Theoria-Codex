package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.related.RelatedPostsRequest
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PostId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerRelatedStripTest {
    @get:Rule val compose = createComposeRule()
    private val image = ImageRef(null, "/missing/related.jpg", "image/jpeg")
    private val seed = testPost(sourcePostId = "seed").copy(preview = image, full = image)
    private val related = seed.copy(id = PostId(seed.id.source, "related"))

    @Test fun `collapse retains recommendations while unrelated navigation hides only the viewer stripe`() {
        val current = mutableStateOf(seed.id)
        var dismissed = 0
        var selected: PostId? = null
        compose.setContent {
            MaterialTheme {
                ViewerRelatedStrip(RelatedPostsUiState.Loaded(RelatedPostsRequest(seed, 0, 1), listOf(related)),
                    listOf(related), current.value, { selected = it.id; current.value = it.id }, { dismissed++ }, {})
            }
        }
        compose.onNodeWithContentDescription("Show related posts").performClick()
        compose.onNodeWithContentDescription("Open post related").performClick()
        compose.runOnIdle { assertEquals(related.id, selected) }
        compose.onNodeWithContentDescription("Collapse related posts").performClick()
        compose.onNodeWithContentDescription("Open post related").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show related posts").performClick()
        compose.runOnIdle { current.value = PostId(seed.id.source, "unrelated") }
        compose.onNodeWithContentDescription("Show related posts").assertDoesNotExist()
        compose.onNodeWithContentDescription("Collapse related posts").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun `failed related request remains retryable and explicit dismiss clears its source shelf`() {
        var dismissed = 0
        var retries = 0
        compose.setContent {
            MaterialTheme {
                ViewerRelatedStrip(RelatedPostsUiState.Failed(RelatedPostsRequest(seed, 0, 1), "offline"),
                    emptyList(), seed.id, {}, { dismissed++ }, { retries++ })
            }
        }
        compose.onNodeWithContentDescription("Show related posts").performClick()
        compose.onNodeWithText("Could not load related posts. Retry").performClick()
        compose.onNodeWithContentDescription("Dismiss related posts").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, dismissed) }
    }
}
