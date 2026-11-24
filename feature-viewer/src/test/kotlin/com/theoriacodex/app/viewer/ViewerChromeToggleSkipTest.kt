package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
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

    @Test fun `a chrome toggle leaves the pages and the overlay host alone (ADR 0166)`() {
        val launch = ViewerSession(listOf(testPost(sourcePostId = "still")), ViewerLaunchContext("q", 0, ViewerStreamSource.SEARCH, 0), sessionId = "toggle")
        val state = mutableStateOf(reduceViewerState(ViewerUiState.Empty, launch.toReplaceViewerSessionAction()).state)
        fun reduce(action: ViewerAction) { state.value = reduceViewerState(state.value, action).state }
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress(): Boolean = tracing
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) { executed += info.substringBefore(" (") }
            override fun traceEventEnd() = Unit
        })
        compose.setContent {
            val host = remember { ViewerScreenHost(onAction = ::reduce) }
            val config = remember { ViewerScreenConfig() }
            val contextActions = remember { ViewerContextActions() }
            MaterialTheme { ViewerScreen(uiState = state.value, host = host, config = config, contextActions = contextActions) }
        }
        compose.waitForIdle()

        tracing = true
        compose.runOnIdle { reduce(ViewerAction.ToggleChrome) }
        compose.waitForIdle()
        tracing = false

        assertTrue("the toggle recomposed something", executed.any { it.endsWith(".ViewerLoadedScreen") })
        val skipped = listOf("ViewerChromeOverlays", "ViewerContentSurface", "ViewerPostPagerSurface", "ViewerPostPage")
        assertEquals(emptyList<String>(), executed.filter { name -> skipped.any { name == "com.theoriacodex.app.viewer.$it" } }.distinct())
    }
}
