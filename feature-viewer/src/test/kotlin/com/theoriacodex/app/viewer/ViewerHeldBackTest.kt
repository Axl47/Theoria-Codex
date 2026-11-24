package com.theoriacodex.app.viewer

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerHeldBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val holding = mutableStateOf(true)
    private val viewerShown = mutableStateOf(true)
    private var backsBehind = 0

    private fun showReturningViewer() {
        compose.setContent {
            // The screen behind the Viewer.
            BackHandler { backsBehind++ }
            if (viewerShown.value) ViewerHeldBackHandler(holding.value)
        }
    }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun `a Back pressed during the return runs once, after the return ends`() {
        showReturningViewer()
        pressBack()
        pressBack()
        compose.waitForIdle()
        assertEquals(0, backsBehind)

        holding.value = false
        compose.waitForIdle()
        assertEquals(1, backsBehind)
    }

    @Test fun `a Back held until the Viewer leaves runs on the screen behind it`() {
        showReturningViewer()
        pressBack()
        compose.waitForIdle()

        viewerShown.value = false
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, backsBehind)
    }
}
