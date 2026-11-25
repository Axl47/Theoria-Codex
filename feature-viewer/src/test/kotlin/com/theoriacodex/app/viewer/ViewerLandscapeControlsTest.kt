package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerLandscapeControlsTest {
    @get:Rule val compose = createComposeRule()
    private val image = ImageRef(null, "/missing/landscape-controls.jpg", "image/jpeg")
    private val posts = (1..12).map { testPost(sourcePostId = "p$it").copy(preview = image, full = image, media = listOf(image)) }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun `in landscape the slideshow button sits in the strip's row, beside the strip instead of over the media (ADR 0166)`() {
        val (button, strip) = showViewer()
        assertTrue("$button should share the strip's row $strip", button.center.y in strip.top..strip.bottom)
        assertTrue("$button should sit right of the strip $strip", button.left >= strip.right)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-port")
    fun `in portrait the slideshow button stays above the strip (ADR 0166)`() {
        val (button, strip) = showViewer()
        assertTrue("$button should sit above the strip $strip", button.bottom <= strip.top)
    }

    private fun showViewer(): Pair<Rect, Rect> {
        val session = ViewerSession(posts, ViewerLaunchContext("query", 0, ViewerStreamSource.SEARCH, 0), sessionId = "landscape-controls")
        val state = mutableStateOf(reduceViewerState(ViewerUiState.Empty, session.toReplaceViewerSessionAction()).state)
        compose.setContent {
            MaterialTheme {
                ViewerScreen(uiState = state.value, host = ViewerScreenHost(onAction = { state.value = reduceViewerState(state.value, it).state }))
            }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithTag("viewer-slideshow").fetchSemanticsNode().boundsInRoot
        val strip = compose.onNodeWithTag("viewer-post-navigation-strip").fetchSemanticsNode().boundsInRoot
        return button to strip
    }
}
