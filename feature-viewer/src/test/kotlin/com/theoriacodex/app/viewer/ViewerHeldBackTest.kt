package com.theoriacodex.app.viewer

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    private val poppedDestination = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
    }

    private fun showReturningViewer() {
        compose.setContent {
            if (viewerShown.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides poppedDestination) {
                    ViewerHeldBackHandler(holding.value, remember { ViewerScreenState() })
                }
            }
            BackHandler { backsBehind++ }
        }
    }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun leave() {
        viewerShown.value = false
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `Backs pressed during the return run once, on the screen behind, after the Viewer leaves`() {
        showReturningViewer()
        pressBack()
        pressBack()
        compose.waitForIdle()
        assertEquals(0, backsBehind)

        leave()
        assertEquals(1, backsBehind)
    }

    @Test fun `a hold that ends while the Viewer stays drops its Back`() {
        showReturningViewer()
        pressBack()
        holding.value = false
        compose.waitForIdle()
        leave()
        assertEquals(0, backsBehind)
    }
}
