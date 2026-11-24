package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.app.search.searchMediaTestTag
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedGridVisibilityTest {
    @get:Rule val compose = createComposeRule()

    private val posts = (0 until 12).map { index ->
        testPost(sourcePostId = "post-$index", preview = ImageRef(null, null, "image/jpeg"), full = null, title = "Post $index")
    }
    private val reported = mutableSetOf<PostId>()
    private val grid = LazyStaggeredGridState()

    @Test fun `cards report visible exactly while their media shows inside the grid`() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(400.dp, 600.dp)) {
                    Box(Modifier.fillMaxWidth().height(120.dp))
                    AdaptivePostGrid(posts, grid, Modifier.weight(1f)) {
                        items(posts, key = { it.id.sourcePostId }) { post ->
                            SearchResultCard(post, pixivUgoiraClient = null, onClick = {}, onViewportChanged = { visible ->
                                if (visible) reported += post.id else reported -= post.id
                            })
                        }
                    }
                }
            }
        }
        assertReportsMatchScreen()
        assertTrue(posts.first().id in reported)

        val mediaHeight = compose.onNodeWithTag(searchMediaTestTag(posts.first().id), useUnmergedTree = true)
            .fetchSemanticsNode().size.height
        compose.runOnIdle { grid.dispatchRawDelta(mediaHeight + 4f) }
        assertReportsMatchScreen()
        compose.onNodeWithTag(searchCardTestTag(posts.first().id), useUnmergedTree = true).assertIsDisplayed()
        assertFalse("media above the grid, under other content, is not visible", posts.first().id in reported)

        compose.runOnIdle { grid.requestScrollToItem(8) }
        assertReportsMatchScreen()
        assertFalse(posts.first().id in reported)
    }

    @Test fun `a grid laid out beside the window, as in a tab swipe, reports no card visible`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 600.dp).offset(x = 5000.dp)) {
                    AdaptivePostGrid(posts, grid, Modifier.fillMaxSize()) {
                        items(posts, key = { it.id.sourcePostId }) { post ->
                            SearchResultCard(post, pixivUgoiraClient = null, onClick = {}, onViewportChanged = { visible ->
                                if (visible) reported += post.id else reported -= post.id
                            })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(searchMediaTestTag(posts.first().id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(emptySet<PostId>(), reported)
    }

    private fun assertReportsMatchScreen() {
        compose.waitForIdle()
        val shown = posts.filter(::mediaShown).map(Post::id).toSet()
        assertTrue(shown.isNotEmpty())
        assertEquals(shown, reported)
    }

    private fun mediaShown(post: Post): Boolean {
        val tag = searchMediaTestTag(post.id)
        return compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
            compose.onNodeWithTag(tag, useUnmergedTree = true).isDisplayed()
    }
}
