package com.theoriacodex.app.ui

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenExitFramesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `leaving a screen with the back arrow never blends it with the screen underneath (ADR 0218)`() {
        val navigation = openSeries()
        compose.runOnIdle { navigation.popBackStack() }
        assertFramesNeverBlend("Back arrow", 30)
        assertHomeShown()
    }

    @Test fun `leaving a screen with the back gesture never blends it with the screen underneath (ADR 0218)`() {
        openSeries()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        assertFramesNeverBlend("Gesture start", 2)
        listOf(0.2f, 0.5f, 0.8f).forEach { progress ->
            compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(progress * 400f, 400f, progress, BackEventCompat.EDGE_LEFT)) }
            assertFramesNeverBlend("Gesture at $progress", 3)
        }
        compose.runOnIdle { dispatcher.onBackPressed() }
        assertFramesNeverBlend("Gesture commit", 30)
        assertHomeShown()
    }

    private fun openSeries(): NavHostController {
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            MaterialTheme(colorScheme = darkColorScheme(background = SCREEN)) {
                Box(Modifier.fillMaxSize().background(BEHIND).testTag("root")) {
                    TheoriaScreenHost(navigation, AppRoute.Home) {
                        opaqueScreen(AppRoute.Home) { Box(Modifier.fillMaxSize().background(HOME)) }
                        opaqueScreen(AppRoute.Settings) {
                            Box(Modifier.fillMaxSize()) { Box(Modifier.size(40.dp).align(Alignment.Center).background(TEXT)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { navigation.navigate(AppRoute.Settings) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return navigation
    }

    private fun assertFramesNeverBlend(label: String, frames: Int) {
        repeat(frames) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val pixels = compose.onNodeWithTag("root").captureToImage().toPixelMap()
            for (x in 0 until pixels.width step 10) {
                val pixel = pixels[x, pixels.height / 2]
                assertTrue("$label frame $frame at x=$x shows $pixel", listOf(SCREEN, HOME, TEXT).any { it.matches(pixel) })
            }
        }
    }

    private fun assertHomeShown() {
        compose.mainClock.advanceTimeBy(1_000)
        val pixel = compose.onNodeWithTag("root").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        assertTrue("Home should be back: $pixel", HOME.matches(pixel))
    }

    private fun Color.matches(other: Color) =
        abs(red - other.red) < 0.02f && abs(green - other.green) < 0.02f && abs(blue - other.blue) < 0.02f

    private companion object {
        val BEHIND = Color.Blue
        val SCREEN = Color.Black
        val HOME = Color.Red
        val TEXT = Color.Green
    }
}
