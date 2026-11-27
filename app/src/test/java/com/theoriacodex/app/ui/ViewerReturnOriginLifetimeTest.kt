package com.theoriacodex.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.trails.TrailPage
import com.theoriacodex.app.ui.trails.TrailFeed
import com.theoriacodex.app.ui.trails.TrailHandoffViewModel
import com.theoriacodex.app.ui.trails.TrailLevel
import com.theoriacodex.app.ui.trails.TrailTestData
import com.theoriacodex.app.ui.trails.rememberViewerLevel
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnLifetime
import com.theoriacodex.app.viewer.ViewerReturnOriginLifetime
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
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
    private val nextPost = testPost(sourcePostId = "tapped-next").id
    private val viewerReturn = ViewerReturnTransition()
    private lateinit var navigation: NavHostController
    private var trailSession: String? = null

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

    @Test fun `a Viewer leaving the back stack never takes a newer Viewer's card (ADR 0220)`() {
        openViewerThenCover()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle { viewerReturn.begin(post) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigation.popBackStack() }
        compose.mainClock.advanceTimeBy(HALF_HOLD_MS)
        compose.runOnIdle { openViewer(nextPost) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(returnsToCard(nextPost))
    }

    @Test fun `a Viewer under a second card-opened Viewer still closes into its own card (ADR 0220)`() {
        openViewerThenCover()
        compose.runOnIdle { openViewer(nextPost, grid = "creator") }
        compose.waitForIdle()
        compose.runOnIdle { viewerReturn.begin(nextPost) }
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle {
            viewerReturn.begin(post)
            assertEquals("feed", viewerReturn.request?.gridId)
        }
    }

    @Test fun `a Viewer under a trail opened from a full screen still closes into its own card (ADR 0220)`() {
        openViewerThenCover()
        compose.runOnIdle { openTrail() }
        compose.waitForIdle()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle { navigation.popBackStack() }
        compose.waitForIdle()
        assertTrue(returnsToCard())
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
                        if (rememberViewerLevel(navigation, entry, trailSession).isRoot) ViewerReturnOriginLifetime()
                        ViewerReturnLifetime(this) { navigation.currentDestination?.route != AppRoute.Viewer }
                        Box(Modifier.fillMaxSize())
                    }
                    opaqueScreen(AppRoute.Settings) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { openViewer(post) }
        compose.waitForIdle()
        compose.runOnIdle { navigation.navigate(AppRoute.Settings) }
        compose.waitForIdle()
    }

    private fun openViewer(tapped: PostId, grid: String = "feed") {
        viewerReturn.openedFrom(gridId = grid, post = tapped)
        viewerReturn.launching(listOf(tapped), keepsRoot = false)
        navigation.navigate(AppRoute.Viewer)
    }

    private fun openTrail() {
        val feed = TrailFeed("trail:cat", { TrailPage(listOf(TrailTestData.post("cat")), true) }, TrailTestData.related(), CoroutineScope(Job()))
        ViewModelProvider(compose.activity)[TrailHandoffViewModel::class.java].hand(TRAIL_SESSION, TrailLevel("cat", "tag:cat", feed))
        trailSession = TRAIL_SESSION
        viewerReturn.launching(listOf(nextPost), keepsRoot = false)
        navigation.navigate(AppRoute.Viewer)
    }

    private fun returnsToCard(tapped: PostId = post): Boolean = compose.runOnIdle {
        viewerReturn.begin(tapped)
        (viewerReturn.request != null).also { viewerReturn.finishLanding() }
    }

    private companion object {
        const val HALF_HOLD_MS = 350L
        const val TRAIL_SESSION = "trail-session"
    }
}
