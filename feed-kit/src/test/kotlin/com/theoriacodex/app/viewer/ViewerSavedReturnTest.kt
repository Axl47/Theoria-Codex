package com.theoriacodex.app.viewer

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.ui.components.PostShelfSection
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
@Config(application = Application::class)
class ViewerSavedReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `visible Saved card supplies its own return bounds (ADR 0216)`() = checkSavedReturn(shelfIndex = 0, postIndex = 0)
    @Test fun `Saved scrolls vertically and horizontally to an offscreen exited post`() = checkSavedReturn(shelfIndex = 2, postIndex = 15)

    private fun checkSavedReturn(shelfIndex: Int, postIndex: Int) {
        val transition = ViewerReturnTransition()
        val shelves = listOf("likes", "followed", "searches").map(ViewerReturnShelfId::Saved)
        val posts = (0..20).map { post(it.toString()) }
        val rows = mutableMapOf<ViewerReturnShelfId, LazyListState>()
        var origin: String? = null
        var viewport = Rect.Zero
        val feed = ViewerReturnTestFeed()
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                feed.Content {
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
                                    PostShelfSection(shelf.codexId, posts.size, posts, false, false, {}, {}, {}) {
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
        }
        compose.runOnIdle { transition.openedFrom(origin, shelfId = shelves[shelfIndex]) }
        feed.closeViewerOnto(compose, transition, posts[postIndex].id)
        compose.runOnIdle {
            val target = requireNotNull(transition.targetBounds) { "Saved should resolve a real card, not use the fallback" }
            assertTrue("The card must be fully visible: $target in $viewport",
                target.left >= viewport.left - 1f && target.right <= viewport.right + 1f)
            assertTrue("The selected carousel must be visible", target.top >= viewport.top && target.bottom <= viewport.bottom)
            val row = requireNotNull(rows[shelves[shelfIndex]])
            if (postIndex > 0) {
                assertTrue(row.firstVisibleItemIndex > 0)
                assertEquals(0, requireNotNull(rows[shelves.first()]).firstVisibleItemIndex)
            } else {
                assertEquals("A visible card leaves its shelf in place", 0 to 0, row.firstVisibleItemIndex to row.firstVisibleItemScrollOffset)
            }
        }
    }

    @Test fun `a shelf under the column's top chrome is revealed just below it`() {
        val transition = ViewerReturnTransition()
        val shelves = (0..5).map { ViewerReturnShelfId.Saved("shelf-$it") }
        val posts = (0..3).map { post(it.toString()) }
        lateinit var column: LazyListState
        var origin: String? = null
        var viewport = Rect.Zero
        var barPx = 0f
        compose.setContent {
            CompositionLocalProvider(LocalViewerReturnTransition provides transition) {
                column = rememberLazyListState()
                barPx = with(LocalDensity.current) { 80.dp.toPx() }
                ViewerReturnColumn(shelves, column) {
                    val gridId = LocalViewerReturnGrid.current
                    origin = gridId
                    LazyColumn(state = column, contentPadding = PaddingValues(top = 80.dp),
                        modifier = Modifier.height(500.dp).onGloballyPositioned { viewport = it.boundsInWindow() }) {
                        items(shelves) { shelf ->
                            val row = rememberLazyListState()
                            ViewerReturnShelf(shelf, posts.map { it.id }, row) {
                                LazyRow(state = row, modifier = Modifier.height(200.dp)) {
                                    items(posts) { post ->
                                        Box(Modifier.width(120.dp).height(200.dp).onGloballyPositioned {
                                            transition.recordBounds(gridId, post.id, it.boundsInWindow(clipBounds = false), shelf)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { column.dispatchRawDelta(with(compose.density) { 230.dp.toPx() }) }
        compose.runOnIdle {
            transition.openedFrom(origin, shelfId = shelves[1])
            transition.begin(posts[0].id)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val target = requireNotNull(transition.targetBounds)
            assertEquals(viewport.top + barPx, target.top, 2f)
        }
    }

    private fun post(id: String) = Post(
        id = PostId(SourceKey.GELBOORU, id), preview = ImageRef(null, null, null), full = null,
        pageUrl = null, width = 800, height = 1000, canonicalTags = emptyList(), rawTags = emptyList(),
        authorName = null, createdAtEpochMs = null,
    )
}
