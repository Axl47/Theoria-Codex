package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.TwoColumnPostStaggeredGrid
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerReturnTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val postId = PostId(SourceKey.GELBOORU, "return")

    @Test fun `only the originating grid and current post can supply a centered target`() {
        val transition = ViewerReturnTransition()
        val target = Rect(10f, 200f, 210f, 500f)
        transition.openedFrom("origin")
        transition.begin(postId)
        transition.recordBounds("other", postId, target)
        transition.centered("other", postId)
        assertNull(transition.targetBounds)
        transition.recordBounds("origin", postId, target)
        assertNull(transition.targetBounds)
        transition.centered("origin", postId)
        assertEquals(target, transition.targetBounds)
        transition.clear()
        assertNull(transition.request)
        assertNull(transition.targetBounds)
    }

    @Test fun `return geometry uses window coordinates and uniformly fills the destination crop`() {
        val source = Rect(20f, 40f, 1020f, 2040f)
        val target = Rect(120f, 640f, 420f, 940f)
        val start = viewerReturnGeometry(source, target, 0.5f, 0f)
        assertEquals(1f, start.scale)
        val end = viewerReturnGeometry(source, target, 0.5f, 1f)
        assertEquals(Rect(100f, 600f, 400f, 900f), end.crop)
        assertEquals(0.3f, end.scale, 0.001f)
        assertEquals(-250f, end.translationX, 0.001f)
        assertEquals(-250f, end.translationY, 0.001f)
        assertEquals(-250f, viewerReturnCenterScroll(100, 300, 0, 1000), 0.001f)
        assertTrue(viewerReturnGeometry(source, null, null, 1f).scale.isFinite())
    }

    @Test fun `return reveals an offscreen post and centers it before publishing its bounds`() {
        val transition = ViewerReturnTransition()
        val posts = (0..60).map { index -> post(index.toString()) }
        var origin: String? = null
        var viewport = Rect.Zero
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                TwoColumnPostStaggeredGrid(
                    posts, rememberLazyStaggeredGridState(),
                    Modifier.height(600.dp).onGloballyPositioned { viewport = it.boundsInWindow() },
                ) { _, post ->
                    val gridId = LocalViewerReturnGrid.current
                    origin = gridId
                    Box(Modifier.fillMaxWidth().height(140.dp).onGloballyPositioned {
                        transition.recordBounds(gridId, post.id, it.boundsInWindow(clipBounds = false))
                    })
                }
            }
        }
        compose.runOnIdle {
            transition.openedFrom(origin)
            transition.begin(posts[40].id)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val target = transition.targetBounds
            assertNotNull(target)
            assertEquals(viewport.center.y, requireNotNull(target).center.y, 2f)
        }
    }

    @Test fun `navigation retains outgoing media until the return motion completes`() {
        val returning = ViewerReturnTransition()
        lateinit var navigation: NavHostController
        var progress = 0f
        compose.setContent {
            navigation = rememberNavController()
            CompositionLocalProvider(LocalViewerReturnTransition provides returning) {
                NavHost(navigation, startDestination = "feed", enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }) {
                    composable("feed") { Box(Modifier.fillMaxSize()) }
                    composable("viewer") {
                        ViewerReturnLifetime(this)
                        val value = rememberViewerReturnProgress()
                        SideEffect { progress = value }
                        Box(Modifier.fillMaxSize().testTag("returning media"))
                    }
                }
            }
        }
        compose.runOnIdle { navigation.navigate("viewer") }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            returning.openedFrom("feed")
            returning.begin(postId)
            returning.recordBounds("feed", postId, Rect(0f, 200f, 200f, 500f))
            returning.centered("feed", postId)
            navigation.popBackStack()
        }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("returning media").assertExists()
        compose.runOnIdle { assertTrue("Return should be in motion: $progress", progress > 0f && progress < 1f) }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(1f, progress, 0.001f) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("returning media").assertDoesNotExist()
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(url = null, localPath = null, mime = null), full = null,
        pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
