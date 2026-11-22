package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.ui.trails.TrailTestData.post
import com.theoriacodex.app.ui.trails.TrailTestData.related
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailFeedTest {
    /** Serves [pages] in order; a null page fails. */
    private class ScriptedPager(vararg pages: TrailPage?) : TrailPager {
        private val remaining = pages.toMutableList()
        var calls = 0
        override suspend fun next(): TrailPage {
            calls++
            return remaining.removeAt(0) ?: error("page failed")
        }
    }

    private fun page(vararg ids: String, more: Boolean) = TrailPage(ids.map { post(it) }, more)
    private fun TestScope.feed(pager: TrailPager, scope: CoroutineScope = backgroundScope) = TrailFeed("trail:t", pager, related(), scope)
    private fun TrailFeed.ids() = viewerFeed.results.map { it.id.sourcePostId }

    @Test
    fun `opens only once the first page holds posts`() = runTest {
        assertTrue(feed(ScriptedPager(page("a", more = true))).start().isNotEmpty())
        assertTrue(feed(ScriptedPager(page(more = false))).start().isEmpty())
        assertTrue(feed(ScriptedPager(null)).start().isEmpty())
    }

    @Test
    fun `a filtered fork pages until something shows, up to three pages`() = runTest {
        val onlyB: (List<Post>) -> List<Post> = { posts -> posts.filter { it.id.sourcePostId.startsWith("b") } }
        val found = ScriptedPager(page("a1", more = true), page("a1", "b1", more = true))
        assertEquals(listOf("b1"), feed(found).start(onlyB).map { it.id.sourcePostId })

        val never = ScriptedPager(page("a1", more = true), page("a2", more = true), page("a3", more = true), page("b", more = true))
        assertTrue(feed(never).start(onlyB).isEmpty())
        assertEquals(3, never.calls)
    }

    @Test
    fun `pages as the Viewer asks, and keeps what it has when a page fails`() = runTest {
        val pager = ScriptedPager(page("a", more = true), null, page("a", "b", more = false))
        val feed = feed(pager, this)
        feed.start()

        feed.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf("a"), feed.ids())
        assertFalse(feed.viewerFeed.loadingMore)

        feed.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), feed.ids())

        feed.loadNextPage()
        advanceUntilIdle()
        assertEquals(3, pager.calls)
    }

    @Test
    fun `a page that outlasts the pager's own timeout fails like any other, and paging goes on`() = runTest {
        var calls = 0
        val pager = TrailPager {
            calls++
            when (calls) {
                1 -> page("a", more = true)
                2 -> withTimeout(10) { awaitCancellation() }
                else -> page("a", "b", more = false)
            }
        }
        val feed = feed(pager, this)
        feed.start()

        feed.loadNextPage()
        advanceUntilIdle()
        assertFalse(feed.viewerFeed.loadingMore)
        assertTrue(feed.viewerFeed.canLoadMore)

        feed.loadNextPage()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), feed.ids())
    }

    @Test
    fun `a resolved post replaces its stub`() = runTest {
        val feed = feed(ScriptedPager(page("a", more = false)))
        feed.start()
        val resolved: Post = post("a").copy(title = "resolved")

        feed.rememberResolvedPost(resolved)

        assertEquals("resolved", feed.viewerFeed.results.single().title)
    }
}
