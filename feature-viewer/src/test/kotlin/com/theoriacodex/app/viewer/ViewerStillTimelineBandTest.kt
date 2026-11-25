package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-port")
class ViewerStillTimelineBandTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetLoader() = coil.Coil.reset()

    @Test
    fun `a gallery page tagged animated that decodes as a still keeps the page scrubber and no empty timeline band (ADR 0166)`() {
        installHangingLoader(loadsImmediately = ".webp")
        val pages = (1..3).map { ImageRef("https://example.com/page-$it.webp", null, "image/webp", isAnimated = true) }
        val gallery = testPost(sourcePostId = "gallery").copy(preview = pages.first(), full = pages.first(), media = pages)
        val others = (1..2).map { testPost(sourcePostId = "p$it") }
        val session = ViewerSession(listOf(gallery) + others, ViewerLaunchContext("query", 0, ViewerStreamSource.SEARCH, 0), sessionId = "still-band")
        val state = mutableStateOf(reduceViewerState(ViewerUiState.Empty, session.toReplaceViewerSessionAction()).state)
        compose.setContent {
            MaterialTheme {
                ViewerScreen(uiState = state.value, host = ViewerScreenHost(onAction = { state.value = reduceViewerState(state.value, it).state }))
            }
        }
        compose.waitForIdle()

        val strip = compose.onNodeWithTag("viewer-post-navigation-strip").fetchSemanticsNode().boundsInRoot
        val scrubber = compose.onNodeWithTag("viewer-page-scrubber").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("viewer-dock").fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { (dock.top - strip.bottom).toDp() }
        assertTrue("strip $strip, scrubber $scrubber, dock $dock: $gap between strip and dock", gap < VIEWER_TIMELINE_HEIGHT)
    }
}
