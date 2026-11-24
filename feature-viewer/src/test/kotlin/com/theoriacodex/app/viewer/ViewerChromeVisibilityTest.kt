package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
        val visible = mutableStateOf(true)
        compose.setContent { Box { ViewerChromeVisibility(visible.value, Modifier) { Text("Dock") } } }
        compose.onNodeWithText("Dock").assertExists()

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible.value = false }
        var frames = 0
        while (compose.onAllNodesWithText("Dock").fetchSemanticsNodes().isNotEmpty() && frames < MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            frames += 1
        }
        assertTrue("chrome stayed for $frames frames", frames <= 2)
    }

    private companion object {
        const val MAX_FRAMES = 30
    }
}
