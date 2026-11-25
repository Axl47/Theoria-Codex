package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.theoriacodex.app.viewer.state.ViewerAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w64dp-h64dp-mdpi")
class ViewerScrubChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a drag-seek on hidden chrome never shows it, from its first frame (ADR 0166)`() {
        val screen = ViewerScreenState()
        val chromeVisible = mutableStateOf(false)
        val controls = ViewerControls(
            { action ->
                when (action) {
                    ViewerAction.ToggleChrome -> chromeVisible.value = !chromeVisible.value
                    is ViewerAction.SetChromeVisible -> chromeVisible.value = action.visible
                    else -> Unit
                }
            },
            screen, overviewVisible = false, chromeVisible = chromeVisible, opening = false, returning = false,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val fade = rememberViewerScrubFade(screen)
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("root")) {
                if (chromeVisible.value) Box(Modifier.fillMaxSize().then(fade).background(Color.White))
            }
        }
        compose.mainClock.advanceTimeByFrame()

        onUi { controls.beginScrub() }
        repeat(4) { assertFalse("chrome showed in scrub frame $it", nextFrameShowsChrome()) }
        onUi { screen.timelineInteractionActive = true }
        repeat(20) { assertFalse("chrome showed once the timeline took the scrub, frame $it", nextFrameShowsChrome()) }
        onUi {
            controls.endScrub()
            screen.timelineInteractionActive = false
        }
        repeat(20) { assertFalse("chrome showed after the scrub, frame $it", nextFrameShowsChrome()) }

        onUi { controls.toggleChrome() }
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("chrome shown on purpose still appears", nextFrameShowsChrome())
    }

    private fun onUi(change: () -> Unit) = compose.runOnUiThread {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun nextFrameShowsChrome(): Boolean {
        compose.mainClock.advanceTimeByFrame()
        val pixels = compose.onNodeWithTag("root").captureToImage().toPixelMap()
        return (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y].red > 0.05f } }
    }
}
