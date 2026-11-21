package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerAction
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
class ViewerOverviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a feed page arriving leaves an open overview open`() {
        val gallery = testPost(sourcePostId = "gallery").copy(media = (1..3).map { ImageRef(null, "/missing-$it.jpg", "image/jpeg") })
        val launch = ViewerSession(listOf(gallery), ViewerLaunchContext("q", 0, ViewerStreamSource.SEARCH, 0), sessionId = "overview")
        val state = mutableStateOf(reduceViewerState(ViewerUiState.Empty, launch.toReplaceViewerSessionAction()).state)
        fun reduce(action: ViewerAction) { state.value = reduceViewerState(state.value, action).state }
        compose.setContent {
            MaterialTheme {
                ViewerScreen(
                    uiState = state.value, creatorBrowsingSources = emptySet(), onAction = ::reduce,
                    onRemoveIncludeTerm = { _, _ -> }, onRemoveExcludeTerm = { _, _ -> }, onGoToSearch = {},
                )
            }
        }
        compose.runOnIdle { reduce(ViewerAction.ToggleOverview) }
        compose.runOnIdle { assertTrue(state.value.overview.visible) }
        compose.runOnIdle { reduce(ViewerAction.AppendPosts(requireNotNull(state.value.session), listOf(testPost(sourcePostId = "next")))) }
        compose.runOnIdle { assertTrue(state.value.overview.visible) }
    }
}
