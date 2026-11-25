package com.theoriacodex.app.ui.trails

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.AppRoute
import com.theoriacodex.app.ui.TheoriaScreenHost
import com.theoriacodex.app.ui.opaqueScreen
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnLifetime
import com.theoriacodex.app.viewer.ViewerReturnTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class ViewerReturnOriginLifetimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val post = testPost(sourcePostId = "tapped").id
    private val viewerReturn = ViewerReturnTransition()
    private lateinit var navigation: NavHostController

    @Test fun `a Viewer covered by a full screen still closes into its card (ADR 0220)`() {
        openViewerThenCover()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        assertTrue(returnsToCard())
    }

    @Test fun `the card origin outlives the Viewer's closing hold and ends with it (ADR 0220)`() {
        openViewerThenCover()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle { viewerReturn.begin(post) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(HALF_HOLD_MS)
        compose.runOnIdle { assertTrue(viewerReturn.request != null) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse(returnsToCard())
    }

    @Test fun `a covered Viewer removed from the back stack lets its card go (ADR 0220)`() {
        openViewerThenCover()
        compose.runOnIdle { navigation.popBackStack(AppRoute.Home, inclusive = false) }
        compose.waitForIdle()
        assertFalse(returnsToCard())
    }

    private fun openViewerThenCover() {
        compose.setContent {
            navigation = rememberNavController()
            CompositionLocalProvider(LocalViewerReturnTransition provides viewerReturn) {
                TheoriaScreenHost(navigation, AppRoute.Home) {
                    opaqueScreen(AppRoute.Home) { Box(Modifier.fillMaxSize()) }
                    composable(AppRoute.Viewer) { entry ->
                        rememberViewerLevel(navigation, entry, null, viewerReturn)
                        ViewerReturnLifetime(this) { navigation.currentDestination?.route != AppRoute.Viewer }
                        Box(Modifier.fillMaxSize())
                    }
                    opaqueScreen(AppRoute.Settings) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            viewerReturn.openedFrom(gridId = "feed", post = post)
            viewerReturn.launching(listOf(post), keepsRoot = false)
            navigation.navigate(AppRoute.Viewer)
        }
        compose.waitForIdle()
        compose.runOnIdle { navigation.navigate(AppRoute.Settings) }
        compose.waitForIdle()
    }

    private fun returnsToCard(): Boolean = compose.runOnIdle {
        viewerReturn.begin(post)
        (viewerReturn.request != null).also { viewerReturn.finishLanding() }
    }

    private companion object {
        const val HALF_HOLD_MS = 350L
    }
}
