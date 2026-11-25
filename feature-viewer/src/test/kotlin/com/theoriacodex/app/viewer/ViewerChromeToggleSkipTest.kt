package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerChromeToggleSkipTest {
    @get:Rule val compose = createComposeRule()

    private var tracing = false
    private val executed = mutableListOf<String>()

    @After fun stopTracing() = Composer.setTracer(null)

    @Test fun `a chrome toggle leaves the pages and the overlay host alone (ADR 0166)`() = assertSkips { reduce -> reduce(ViewerAction.ToggleChrome) }

    @Test fun `a dwell restart in Play Next leaves the pages and the overlay host alone (ADR 0217)`() =
        assertSkips(ViewerAction.SetPlaybackMode(com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT)) { reduce ->
            reduce(ViewerAction.RestartDwell(123_456L, interacting = true))
            reduce(ViewerAction.ToggleChrome)
        }

    @Test fun `a swipe between posts recomposes nothing per frame (ADR 0166)`() {
        render(listOf(testPost(sourcePostId = "first"), testPost(sourcePostId = "second")))
        val entering = traced { compose.onRoot().performTouchInput { down(center); repeat(3) { moveBy(Offset(-width * 0.05f, 0f)) } } }
        val frames = List(4) { traced { compose.onRoot().performTouchInput { moveBy(Offset(-width * 0.05f, 0f)) } }.filter { it.startsWith("com.theoriacodex.") }.toSet() }
        compose.onRoot().performTouchInput { up() }

        assertTrue("the swipe never reached the next post", "com.theoriacodex.app.viewer.ViewerPostPage" in entering)
        assertEquals(emptySet<String>(), frames.reduce { every, frame -> every intersect frame })
    }

    private fun assertSkips(vararg setup: ViewerAction, change: ((ViewerAction) -> Unit) -> Unit) {
        val reduce = render(listOf(testPost(sourcePostId = "still")), *setup)
        val windows = List(3) { traced { compose.runOnIdle { change(reduce) } } }

        assertTrue("the change recomposed something", windows.all { executed -> executed.any { it.endsWith(".ViewerLoadedScreen") } })
        val skipped = listOf("ViewerChromeOverlays", "ViewerContentSurface", "ViewerPostPagerSurface", "ViewerPostPage").map { "com.theoriacodex.app.viewer.$it" }.toSet()
        assertEquals(emptySet<String>(), windows.map { it.toSet() intersect skipped }.reduce { every, window -> every intersect window })
    }

    private fun render(posts: List<Post>, vararg setup: ViewerAction): (ViewerAction) -> Unit {
        val launch = ViewerSession(posts, ViewerLaunchContext("q", 0, ViewerStreamSource.SEARCH, 0), sessionId = "toggle")
        val state = mutableStateOf(setup.fold(reduceViewerState(ViewerUiState.Empty, launch.toReplaceViewerSessionAction()).state) { current, action -> reduceViewerState(current, action).state })
        val reduce: (ViewerAction) -> Unit = { action -> state.value = reduceViewerState(state.value, action).state }
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress(): Boolean = tracing
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) { executed += info.substringBefore(" (") }
            override fun traceEventEnd() = Unit
        })
        compose.setContent {
            val host = remember { ViewerScreenHost(onAction = reduce) }
            val config = remember { ViewerScreenConfig() }
            val contextActions = remember { ViewerContextActions() }
            MaterialTheme { ViewerScreen(uiState = state.value, host = host, config = config, contextActions = contextActions) }
        }
        compose.waitForIdle()
        return reduce
    }

    private fun traced(change: () -> Unit): List<String> {
        executed.clear()
        tracing = true
        change()
        compose.waitForIdle()
        tracing = false
        return executed.toList()
    }
}
