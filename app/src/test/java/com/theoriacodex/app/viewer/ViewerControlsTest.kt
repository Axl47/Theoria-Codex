package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.viewer.state.ViewerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `controls follow chrome and transition state across recomposition`() {
        val actions = mutableListOf<ViewerAction>()
        val onAction: (ViewerAction) -> Unit = { actions += it }
        val screen = ViewerScreenState()
        val chromeVisible = mutableStateOf(true)
        val opening = mutableStateOf(false)
        val returning = mutableStateOf(false)
        lateinit var controls: ViewerControls
        compose.setContent {
            controls = rememberViewerControls(onAction, screen, false, chromeVisible.value, opening.value, returning.value)
        }

        fun interact(): List<ViewerAction> {
            actions.clear()
            compose.runOnIdle { controls.markInteraction() }
            return actions.toList()
        }

        assertEquals("visible chrome stays as it is", emptyList<ViewerAction>(), interact())
        compose.runOnIdle { chromeVisible.value = false }
        assertEquals("hidden chrome opens", listOf<ViewerAction>(ViewerAction.ToggleChrome), interact())
        compose.runOnIdle { opening.value = true }
        assertEquals("an opening Viewer keeps chrome closed", emptyList<ViewerAction>(), interact())
        compose.runOnIdle {
            opening.value = false
            returning.value = true
        }
        assertEquals("a returning Viewer keeps chrome closed", emptyList<ViewerAction>(), interact())
        assertEquals(4, screen.interactionSerial)
    }

    @Test fun `dismissal closes every surface once`() {
        val actions = mutableListOf<ViewerAction>()
        val screen = ViewerScreenState()
        val controls = ViewerControls({ actions += it }, screen, overviewVisible = true, chromeVisible = true, opening = false, returning = false)

        controls.requestDismiss()
        controls.requestDismiss()

        assertEquals(
            listOf(
                ViewerAction.Pause, ViewerAction.HideMetadata, ViewerAction.HideActionsMenu,
                ViewerAction.HidePlaybackSettings, ViewerAction.ToggleOverview,
            ),
            actions,
        )
        assertTrue(screen.pendingDismiss)
    }
}
