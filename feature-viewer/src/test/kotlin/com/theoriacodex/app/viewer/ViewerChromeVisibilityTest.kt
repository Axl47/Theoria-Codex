package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerChromeVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `hidden chrome leaves at once instead of fading out (ADR 0166)`() {
        val visible = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val stage = rememberViewerChromeStage(visible.value)
            Box { ViewerChromeVisibility(stage.value >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier) { Button(onClick = {}) { Text("Dock") } } }
        }
        compose.runOnIdle { visible.value = true }
        var shown = 0
        while (dockNodes() == 0 && shown < MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            shown += 1
        }
        assertTrue("chrome shows", dockNodes() > 0)

        compose.runOnIdle { visible.value = false }
        var frames = 0
        while (dockNodes() > 0 && frames < MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            frames += 1
        }
        assertTrue("chrome hidden during its fade-in stayed for $frames frames", frames <= 1)
    }

    private fun dockNodes() = compose.onAllNodesWithText("Dock", useUnmergedTree = true).fetchSemanticsNodes().size

    private companion object {
        const val MAX_FRAMES = 30
    }
}
