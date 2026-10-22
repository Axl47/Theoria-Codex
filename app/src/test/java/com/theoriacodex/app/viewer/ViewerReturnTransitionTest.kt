package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
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
import com.theoriacodex.app.ui.components.TwoColumnProjectedPostStaggeredGrid
import com.theoriacodex.app.ui.components.RelatedPostsShelf
import com.theoriacodex.app.related.RelatedFeedEntry
import com.theoriacodex.app.related.RelatedFeedProjection
import com.theoriacodex.app.related.RelatedPostsRequest
import com.theoriacodex.app.related.RelatedPostsUiState
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

    @Test fun `Recents and Codex grid centers the exited offscreen post`() = checkGridReturn(projected = false)

    @Test fun `FYP projected grid centers the exited post past inserted shelves`() = checkGridReturn(projected = true)

    private fun checkGridReturn(projected: Boolean) {
        val transition = ViewerReturnTransition()
        val posts = (0..60).map { index -> post(index.toString()) }
        var origin: String? = null
        var viewport = Rect.Zero
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                val card: @Composable (Int, Post) -> Unit = { _, post ->
                    val gridId = LocalViewerReturnGrid.current
                    origin = gridId
                    Box(Modifier.fillMaxWidth().height(140.dp).onGloballyPositioned {
                        transition.recordBounds(gridId, post.id, it.boundsInWindow(clipBounds = false))
                    })
                }
                val modifier = Modifier.height(600.dp).onGloballyPositioned { viewport = it.boundsInWindow() }
                if (projected) {
                    val entries = posts.mapIndexed { index, post -> RelatedFeedEntry.PostEntry(post, index) }
                        .toMutableList<RelatedFeedEntry>()
                    entries.add(5, RelatedFeedEntry.ShelfEntry(
                        RelatedPostsUiState.Loading(RelatedPostsRequest(posts[4], 4, 1)), 4,
                    ))
                    TwoColumnProjectedPostStaggeredGrid(
                        RelatedFeedProjection(entries), rememberLazyStaggeredGridState(), modifier,
                        postContent = { index, post -> card(index, post) },
                        shelfContent = { Box(Modifier.fillMaxWidth().height(180.dp)) },
                    )
                } else {
                    TwoColumnPostStaggeredGrid(posts, rememberLazyStaggeredGridState(), modifier) { index, post ->
                        card(index, post)
                    }
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

    @Test fun `navigation expands from the card and retains media until return completes`() {
        val returning = ViewerReturnTransition()
        lateinit var navigation: NavHostController
        var progress = 0f
        compose.setContent {
            navigation = rememberNavController()
            CompositionLocalProvider(LocalViewerReturnTransition provides returning) {
                NavHost(navigation, startDestination = "feed", enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }) {
                    composable("feed") { Box(Modifier.fillMaxSize().testTag("source feed")) }
                    composable("viewer") {
                        ViewerReturnLifetime(this)
                        val value = rememberViewerReturnProgress()
                        SideEffect { progress = value }
                        Box(Modifier.fillMaxSize().testTag("returning media"))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val origin = Rect(20f, 200f, 220f, 500f)
        compose.runOnIdle {
            returning.openedFrom("feed", origin)
            navigation.navigate("viewer")
        }
        compose.mainClock.advanceTimeBy(96)
        compose.onNodeWithTag("source feed").assertExists()
        compose.onNodeWithTag("returning media").assertExists()
        compose.runOnIdle {
            assertEquals(origin, returning.openingBounds)
            assertTrue("Opening should be partway from card to fullscreen: $progress", progress > 0f && progress < 1f)
        }
        val openingMidpoint = progress
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { assertTrue(progress < openingMidpoint) }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle {
            assertEquals(0f, progress, 0.001f)
            assertNull(returning.openingBounds)
        }
        compose.onNodeWithTag("source feed").assertDoesNotExist()
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

    @Test fun `related return positions both the offscreen shelf and its offscreen card`() {
        val transition = ViewerReturnTransition()
        val related = (0..20).map { post("related-$it") }
        val target = related[15]
        val seed = post("seed")
        val loaded = RelatedPostsUiState.Loaded(RelatedPostsRequest(seed, 29, 1), related)
        val regular = listOf(target) + (1..60).map { post("regular-$it") }
        val entries = regular.mapIndexed { index, post -> RelatedFeedEntry.PostEntry(post, index) }.toMutableList<RelatedFeedEntry>()
        entries.add(30, RelatedFeedEntry.ShelfEntry(loaded, 29))
        val projection = RelatedFeedProjection(entries)
        var origin: String? = null
        var viewport = Rect.Zero
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                TwoColumnProjectedPostStaggeredGrid(
                    projection, rememberLazyStaggeredGridState(),
                    Modifier.height(600.dp).onGloballyPositioned { viewport = it.boundsInWindow() },
                    postContent = { _, post ->
                        val gridId = LocalViewerReturnGrid.current
                        origin = gridId
                        Box(Modifier.fillMaxWidth().height(140.dp).onGloballyPositioned {
                            transition.recordBounds(gridId, post.id, it.boundsInWindow(clipBounds = false))
                        })
                    },
                    shelfContent = {
                        RelatedPostsShelf(
                            state = loaded, posts = related, likedPostIds = emptySet(), pixivUgoiraClient = null,
                            onToggleLike = {}, onOpenPost = { _, _ -> }, onLongPress = {}, onDismiss = {},
                            onRetry = {}, onPreviousPage = {}, onNextPage = {}, onViewportChanged = { _, _ -> },
                            onAuthoritativeDurationKnown = { _, _ -> },
                        )
                    },
                )
            }
        }
        compose.runOnIdle {
            transition.openedFrom(origin, shelfId = ViewerReturnShelfId.Related(seed.id))
            transition.begin(target.id)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val bounds = requireNotNull(transition.targetBounds) { "The related card must replace the centered fallback" }
            assertEquals(viewport.center.x, bounds.center.x, 2f)
            assertTrue("Shelf media should be fully visible: $bounds in $viewport",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
    }

    @Test fun `shelf target waits for both axes and ignores a duplicate regular card`() {
        val transition = ViewerReturnTransition()
        val shelf = ViewerReturnShelfId.Related(post("seed").id)
        val bounds = Rect(20f, 100f, 220f, 400f)
        transition.openedFrom("feed", shelfId = shelf)
        transition.begin(postId)
        transition.recordBounds("feed", postId, bounds)
        transition.centered("feed", postId)
        assertNull(transition.targetBounds)
        transition.recordBounds("feed", postId, bounds, shelf)
        assertNull(transition.targetBounds)
        transition.shelfCentered("feed", postId, shelf)
        assertEquals(bounds, transition.targetBounds)
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(url = null, localPath = null, mime = null), full = null,
        pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
