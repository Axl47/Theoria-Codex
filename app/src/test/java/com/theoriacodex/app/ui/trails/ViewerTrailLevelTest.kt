package com.theoriacodex.app.ui.trails

import com.theoriacodex.app.trails.TrailPage
import com.theoriacodex.app.trails.TrailPager
import com.theoriacodex.app.ui.routes.ViewerRouteEffectCallbacks
import com.theoriacodex.app.ui.trails.TrailTestData.post
import com.theoriacodex.app.ui.trails.TrailTestData.related
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTrailLevelTest {
    private fun level(isFork: Boolean, covered: Boolean = false, feed: TrailFeed? = null) =
        ViewerLevel(isFork, emptyList(), feed, { covered }, {}, { _, _ -> })

    private fun feed(job: Job = Job()) = TrailFeed("trail:t", { TrailPage(listOf(post("a")), true) }, related(), CoroutineScope(job))

    @Test
    fun `a root crumb names its search's tags, else where it was opened from`() {
        val search = Query(QueryMode.Unified, listOf("long_hair", "cat"), emptyList(), SortMode.NEWEST, null, null)

        assertEquals("long hair cat", viewerRootLabel(context(ViewerStreamSource.SEARCH), search))
        assertEquals("Search", viewerRootLabel(context(ViewerStreamSource.SEARCH), null))
        assertEquals("For You", viewerRootLabel(context(ViewerStreamSource.FOR_YOU), null))
    }

    @Test
    fun `only a root no fork covers ends the card return when it leaves`() {
        assertTrue(level(isFork = false).clearsReturnOnDispose())
        assertFalse(level(isFork = false, covered = true).clearsReturnOnDispose())
        assertFalse(level(isFork = true).clearsReturnOnDispose())
    }

    @Test
    fun `a handed fork goes only to the entry claiming its session, once, and a newer one closes it`() {
        val handoff = TrailHandoffViewModel()
        val firstJob = Job()
        val first = TrailLevel("cat", "tag:cat", feed(firstJob))
        handoff.hand("s1", first)

        assertNull(handoff.claim("other"))
        handoff.hand("s2", TrailLevel("dog", "tag:dog", feed()))
        assertTrue(firstJob.isCancelled)
        assertNull(handoff.claim("s1"))
        assertEquals("dog", handoff.claim("s2")?.label)
        assertNull(handoff.claim("s2"))
    }

    @Test
    fun `a fork's dismiss pops only its level, and its own feed pages the trail stream`() = runTest {
        var dismissed = 0
        var popped = 0
        val loaded = mutableListOf<ViewerStreamSource>()
        val base = callbacks(onDismiss = { dismissed++ }, onLoadMore = { loaded += it })
        val pages = mutableListOf<Unit>()
        val trail = TrailFeed("trail:t", TrailPager { pages += Unit; TrailPage(listOf(post("a")), true) }, related(), backgroundScope)
        trail.start()

        val fork = base.forLevel(level(isFork = true, feed = trail)) { popped++ }
        fork.onDismiss()
        fork.onLoadMore(ViewerStreamSource.TRAIL)
        fork.onLoadMore(ViewerStreamSource.SEARCH)
        runCurrent()
        assertEquals(Triple(0, 1, listOf(ViewerStreamSource.SEARCH)), Triple(dismissed, popped, loaded))
        assertEquals(2, pages.size)

        assertSame(base.onDismiss, base.forLevel(level(isFork = false)) { popped++ }.onDismiss)
    }

    private fun context(source: ViewerStreamSource) = ViewerLaunchContext("q", 0, source, 0)

    private fun callbacks(onDismiss: () -> Unit, onLoadMore: (ViewerStreamSource) -> Unit) = ViewerRouteEffectCallbacks(
        onSavePost = { _, _ -> },
        onDownloadMedia = {},
        onToggleLike = {},
        onOpenCreatorProfile = {},
        onApplyTag = { _, _, _ -> },
        onRecoverMedia = { _, _, _, _ -> null },
        onLoadMore = onLoadMore,
        onDismiss = onDismiss,
        onRestorationUnavailable = {},
    )
}
