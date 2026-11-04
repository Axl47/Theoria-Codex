package com.theoriacodex.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodexTransitionFramesTest {
    @get:Rule val compose = createComposeRule()

    /** A blue backdrop behind opaque screens: any blue in a frame means the screen dipped through it. */
    @Test fun `opening and closing a Codex never shows what is behind both screens`() {
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            Box(Modifier.fillMaxSize().background(Color.Blue).testTag("root")) {
                NavHost(
                    navigation, startDestination = "library",
                    enterTransition = { codexEnterTransition() }, exitTransition = { codexUnderneathExit() },
                    popEnterTransition = { codexUnderneathEnter() }, popExitTransition = { codexExitTransition() },
                ) {
                    composable("library") { Box(Modifier.fillMaxSize().background(Color.Red)) }
                    composable("codex") { Box(Modifier.fillMaxSize().background(Color.Green)) }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun assertNoBackdropThrough(transition: () -> Unit, label: String) {
            compose.runOnIdle(transition)
            repeat(30) { frame ->
                compose.mainClock.advanceTimeByFrame()
                val pixel = compose.onNodeWithTag("root").captureToImage().toPixelMap()[200, 400]
                assertEquals("$label frame $frame shows the backdrop: $pixel", 0f, pixel.blue, 0.02f)
            }
        }
        assertNoBackdropThrough({ navigation.navigate("codex") }, "Opening")
        val opened = compose.onNodeWithTag("root").captureToImage().toPixelMap()[200, 400]
        assertTrue("The Codex should have opened: $opened", opened.green > 0.9f)
        assertNoBackdropThrough({ navigation.popBackStack() }, "Closing")
        val closed = compose.onNodeWithTag("root").captureToImage().toPixelMap()[200, 400]
        assertTrue("The library should be back: $closed", closed.red > 0.9f)
    }
}
