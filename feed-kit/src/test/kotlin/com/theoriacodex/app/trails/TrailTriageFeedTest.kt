package com.theoriacodex.app.trails

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.triage.TriageFeed
import com.theoriacodex.app.triage.TriageFeedSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrailTriageFeedTest {
    private val posts = (1..4).map { testPost(sourcePostId = "p$it") }

    /** Answers each [TrailPager.next] with the next scripted page, a null one failing. */
    private class ScriptedPager(vararg pages: TrailPage?) : TrailPager {
        private val pages = ArrayDeque(pages.toList())
        var calls = 0
        override suspend fun next(): TrailPage {
            calls++
            return pages.removeFirst() ?: error("page failed")
        }
    }

    /** Collects every snapshot [this] emits, starting now. */
    private fun TestScope.collected(feed: TriageFeed): List<TriageFeedSnapshot> =
        mutableListOf<TriageFeedSnapshot>().also { seen -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { feed.snapshots.toList(seen) } }

    @Test fun `loads its first page when collected and the next when asked, showing only what visible keeps`() = runTest(UnconfinedTestDispatcher()) {
        val pager = ScriptedPager(TrailPage(posts.take(2), canLoadMore = true), TrailPage(posts, canLoadMore = false))
        val feed = pager.asTriageFeed(backgroundScope) { shown -> shown.filter { it != posts[1] } }
        assertEquals(0, pager.calls)
        val seen = collected(feed)
        assertEquals(TriageFeedSnapshot(listOf(posts[0]), canLoadMore = true, loaded = 2), seen.last())

        feed.loadNextPage()
        assertEquals(TriageFeedSnapshot(listOf(posts[0], posts[2], posts[3]), canLoadMore = false, loaded = 4), seen.last())
        feed.loadNextPage()
        assertEquals(2, pager.calls)
    }

    @Test fun `a page that adds no posts still counts as loaded`() = runTest(UnconfinedTestDispatcher()) {
        val feed = ScriptedPager(TrailPage(posts.take(2), canLoadMore = true), TrailPage(posts.take(2), canLoadMore = true)).asTriageFeed(backgroundScope)
        val seen = collected(feed)
        feed.loadNextPage()
        assertEquals(TriageFeedSnapshot(posts.take(2), canLoadMore = true, loaded = 3), seen.last())
    }

    @Test fun `a failed page keeps what loaded, refuses to page, and retry loads it`() = runTest(UnconfinedTestDispatcher()) {
        val pager = ScriptedPager(TrailPage(posts.take(2), canLoadMore = true), null, TrailPage(posts, canLoadMore = false))
        val feed = pager.asTriageFeed(backgroundScope)
        val seen = collected(feed)
        feed.loadNextPage()
        assertEquals(TriageFeedSnapshot(posts.take(2), canLoadMore = true, loaded = 2, pageFailed = true), seen.last())
        feed.loadNextPage()
        assertEquals(2, pager.calls)

        feed.retryPage()
        assertEquals(TriageFeedSnapshot(posts, canLoadMore = false), seen.last())
    }

    @Test fun `reports loading while a page runs and asks for no second one meanwhile`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val feed = TrailPager { calls++; gate.await(); TrailPage(posts, canLoadMore = true) }.asTriageFeed(backgroundScope)
        val seen = collected(feed)
        assertEquals(true, seen.last().loadingMore)
        feed.loadNextPage()
        feed.retryPage()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals(TriageFeedSnapshot(posts, canLoadMore = true), seen.last())
    }
}
