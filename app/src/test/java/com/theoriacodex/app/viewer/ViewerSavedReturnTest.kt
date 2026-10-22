package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.ui.routes.SavedCodexCarousel
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ViewerSavedReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `visible Saved card supplies its own return bounds`() = checkSavedReturn(shelfIndex = 0, postIndex = 1)
    @Test fun `Saved scrolls vertically and horizontally to an offscreen exited post`() = checkSavedReturn(shelfIndex = 2, postIndex = 15)

    private fun checkSavedReturn(shelfIndex: Int, postIndex: Int) {
        val transition = ViewerReturnTransition()
        val shelves = listOf("likes", "followed", "searches").map(ViewerReturnShelfId::Saved)
        val posts = (0..20).map { post(it.toString()) }
        val rows = mutableMapOf<ViewerReturnShelfId, LazyListState>()
        var origin: String? = null
        var viewport = Rect.Zero
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                val column = rememberLazyListState()
                ViewerReturnColumn(listOf(null) + shelves, column) {
                    origin = LocalViewerReturnGrid.current
                    LazyColumn(state = column,
                        modifier = Modifier.fillMaxWidth().height(500.dp).onGloballyPositioned { viewport = it.boundsInWindow() }) {
                        item { Box(Modifier.fillMaxWidth().height(64.dp)) }
                        items(shelves) { shelf ->
                            val row = rememberLazyListState()
                            rows[shelf] = row
                            ViewerReturnShelf(shelf, posts.map { it.id }, row) {
                                SavedCodexCarousel(shelf.codexId, posts.size, posts, false, false, {}, {}, {}) {
                                    LazyRow(state = row, modifier = Modifier.height(260.dp)) {
                                        items(posts) { post ->
                                            Box(Modifier.width(164.dp)) {
                                                SearchResultCard(post, pixivUgoiraClient = null,
                                                    mediaAspectRatio = 0.8f, showTitle = false, onClick = {})
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            transition.openedFrom(origin, shelfId = shelves[shelfIndex])
            transition.begin(posts[postIndex].id)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val target = requireNotNull(transition.targetBounds) { "Saved should resolve a real card, not use the fallback" }
            assertEquals(viewport.center.x, target.center.x, 2f)
            assertTrue("The selected carousel must be visible", target.top >= viewport.top && target.bottom <= viewport.bottom)
            if (postIndex > 1) {
                assertTrue(requireNotNull(rows[shelves[shelfIndex]]).firstVisibleItemIndex > 0)
                assertEquals(0, requireNotNull(rows[shelves.first()]).firstVisibleItemIndex)
            }
        }
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
