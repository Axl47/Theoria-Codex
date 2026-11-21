package com.theoriacodex.app.search

import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.app.ui.components.FeedDurations
import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationProvenance
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.app.media.mediaDurationStates
import com.theoriacodex.app.related.RelatedFeedEntry
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.SteppedDispatcher
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.LocalFeedComputationContext
import com.theoriacodex.app.viewer.LocalViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTrace

/**
 * Pins what the Search feed shows after every frame (plan `.docs/exec/shared-feed-presenter.html`, Phases 0 and 1). Frames advance by hand and background work runs only when [background] is drained, so each step shows
 * whether a result was computed in the frame itself (on the main thread) or waited for the background. Opening the
 * Viewer removes the feed from composition, as navigating to its destination does; [feedShown] stands in for that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchFeedFramesTest {
    @get:Rule val compose = createComposeRule()

    private val background = SteppedDispatcher()
    private val transition = ViewerReturnTransition()

    // Animated posts with no length in their metadata, so the length filter waits for [durations].
    private val posts = List(12) { testPost(SourceKey.RULE34VIDEO, "v$it") }
    private var state by mutableStateOf(searchState(posts.take(8)))
    private var fab by mutableStateOf(FeedFabRestoreState())
    private var durations by mutableStateOf(emptyMap<MediaDurationKey, MediaDurationState>())
    private val durationStates by derivedStateOf { mediaDurationStates(posts, durations) }
    private var liked by mutableStateOf(emptySet<PostId>())
    private var feedShown by mutableStateOf(true)
    private val actions = mutableListOf<SearchAction>()
    private lateinit var grid: LazyStaggeredGridState

    /** What the grid draws in the last applied composition; an empty list is Search's "no results" state. */
    private var shown: List<String>? = null

    @Test fun `an appended page shows in the frame it arrives, filtering only the page in place`() {
        show()
        state = searchState(posts)

        assertEquals(Frame(ids(0..11), filterPasses = 1), frame())
        assertEquals(0, drain())
    }

    @Test fun `a replaced list shows in the frame it arrives, filtered in place`() {
        show()
        val replacement = List(5) { testPost(SourceKey.RULE34VIDEO, "r$it") }
        state = searchState(replacement)

        assertEquals(Frame(replacement.map { it.id.sourcePostId }, filterPasses = 1), frame())
        assertEquals(0, drain())
    }

    @Test fun `a filter change keeps the old list until its background pass, and a page arriving meanwhile wins`() {
        show()
        liked = setOf(posts[1].id)
        fab = fab.copy(hideLiked = true)
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())

        // The next page lands before the background pass: the whole new list is filtered in place, new filter included.
        state = searchState(posts)
        assertEquals(Frame(ids(0..11) - "v1", filterPasses = 1), frame())
        // The background pass still runs over the old list; its answer is dropped.
        assertEquals(1, drain())
        assertEquals(Frame(ids(0..11) - "v1", filterPasses = 0), frame())
    }

    @Test fun `each arriving length with the length filter on filters what it reveals in place`() {
        lengthFilterOn()
        show()
        assertEquals(Frame(emptyList(), filterPasses = 0), frame())

        // One length per step. The projection answers in the background with a longer presented list, and the
        // visibility filter adds the result for the newly revealed post alone, in the frame itself (FilteredFeedTest
        // checks that this equals a whole pass).
        for (index in 0..3) {
            durations = durations + known(posts[index], SHORT_MS)
            assertEquals(Frame(ids(0 until index), filterPasses = 0), frame())
            assertEquals(0, drain())
            assertEquals(Frame(ids(0..index), filterPasses = 1), frame())
        }

        // A burst inside one frame: one projection answers for all three, and one in-place pass shows them.
        durations = durations + known(posts[4], SHORT_MS)
        durations = durations + known(posts[5], LONG_MS)
        durations = durations + known(posts[6], SHORT_MS)
        assertEquals(Frame(ids(0..3), filterPasses = 0), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..4) + "v6", filterPasses = 1), frame())
    }

    @Test fun `an arriving length that reveals nothing costs no filter pass`() {
        lengthFilterOn()
        durations = known(posts[0], SHORT_MS).let(::mapOf)
        show()
        assertEquals(Frame(ids(0..0), filterPasses = 0), frame())

        // v5 lies behind the frontier, which waits at v1: the projection presents the very same list.
        durations = durations + known(posts[5], SHORT_MS)
        assertEquals(Frame(ids(0..0), filterPasses = 0), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..0), filterPasses = 0), frame())
        assertEquals(0, drain())
    }

    @Test fun `returning from the Viewer with the length filter off reuses the kept result`() {
        show()
        openViewer()

        feedShown = true
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
    }

    @Test fun `returning from the Viewer with the length filter on shows the full list on the first frame`() {
        lengthFilterOn()
        durations = posts.take(8).associate { known(it, SHORT_MS) }
        show()
        openViewer()

        feedShown = true
        // The kept length frame and the kept filter result cover the same list, so nothing is redone.
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
    }

    @Test fun `a filter changed while the Viewer was open is applied in place on the first frame back`() {
        liked = setOf(posts[1].id)
        show()
        openViewer()
        fab = fab.copy(hideLiked = true)

        feedShown = true
        assertEquals(Frame(ids(0..7) - "v1", filterPasses = 1), frame())
        assertEquals(0, drain())
    }

    @Test fun `a filter changed while the Viewer was open applies on the first frame back with the length filter on`() {
        lengthFilterOn()
        durations = posts.take(8).associate { known(it, SHORT_MS) }
        liked = setOf(posts[1].id)
        show()
        openViewer()
        fab = fab.copy(hideLiked = true)

        feedShown = true
        assertEquals(Frame(ids(0..7) - "v1", filterPasses = 1), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7) - "v1", filterPasses = 0), frame())
    }

    @Test fun `a length that resolved while the Viewer was open shows after the projection answers`() {
        lengthFilterOn()
        durations = posts.take(7).associate { known(it, SHORT_MS) }
        show()
        openViewer()
        durations = durations + known(posts[7], SHORT_MS)

        feedShown = true
        // The first frame is the one the Viewer covered; the new length lands as any arriving length does.
        assertEquals(Frame(ids(0..6), filterPasses = 0), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7), filterPasses = 1), frame())
    }

    @Test fun `a page that arrived while the Viewer was open is filtered in place on the first frame back`() {
        show()
        openViewer()
        state = searchState(posts)

        feedShown = true
        assertEquals(Frame(ids(0..11), filterPasses = 1), frame())
        assertEquals(0, drain())
    }

    @Test fun `a page that arrived while the Viewer was open with the length filter on keeps the loaded posts until it is measured`() {
        lengthFilterOn()
        durations = posts.associate { known(it, SHORT_MS) }
        show()
        openViewer()
        state = searchState(posts)

        feedShown = true
        // The kept length frame covers the start of the longer list, as for a page arriving with the feed on screen.
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..11), filterPasses = 1), frame())
    }

    @Test fun `a post snapshot replaced while the Viewer was open shows the old frame until the projection answers`() {
        lengthFilterOn()
        durations = posts.take(8).associate { known(it, SHORT_MS) }
        show()
        openViewer()
        // The Viewer resolved v3 again: a new list, same ids, and a corrected length outside the range.
        state = searchState(posts.take(8).toMutableList().apply { this[3] = this[3].copy(durationMs = LONG_MS) })

        feedShown = true
        // The kept length frame covers a list with the same ids, so the first frame is the one the Viewer covered.
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
        // One background projection reads the new snapshot; the corrected length then hides v3 in place.
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7) - "v3", filterPasses = 1), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7) - "v3", filterPasses = 0), frame())
    }

    @Test fun `a post liked in the Viewer stays until the return finishes, then leaves after a background pass`() {
        fab = fab.copy(hideLiked = true)
        show()
        feedShown = false
        transition.openedFrom("search")
        liked = setOf(posts[2].id)
        transition.retainPosts(setOf(posts[2].id))
        assertEquals(Frame(null, filterPasses = 0), frame())

        feedShown = true
        transition.begin(posts[2].id)
        // The marks changed, so the kept result does not apply and the first frame filters in place.
        assertEquals(Frame(ids(0..7), filterPasses = 1), frame())
        assertEquals(0, drain())

        transition.clear()
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
        assertEquals(1, drain())
        assertEquals(Frame(ids(0..7) - "v2", filterPasses = 0), frame())
    }

    @Test fun `a restore beyond the loaded range asks for a page and lands in the frame the page arrives`() {
        state = searchState(posts.take(8)).copy(
            restoration = SearchRestorationUiState.Restored(false, SearchScrollState(9, 0), 1L),
        )
        show(withGrid = true)
        assertEquals(listOf(SearchAction.LoadNextPage), actions.toList())
        assertEquals(0, grid.firstVisibleItemIndex)

        state = state.copy(content = state.content.copy(results = posts))
        assertEquals(Frame(ids(0..11), filterPasses = 1), frame())
        assertEquals(listOf(SearchAction.LoadNextPage, SearchAction.ScrollRestorationApplied(1L)), actions.toList())
        assertEquals(9, grid.firstVisibleItemIndex)
    }

    private fun openViewer() {
        feedShown = false
        assertEquals(Frame(null, filterPasses = 0), frame())
    }

    private fun show(withGrid: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalFeedComputationContext provides background,
                LocalViewerReturnTransition provides transition,
            ) {
                if (!feedShown) {
                    SideEffect { shown = null }
                    return@CompositionLocalProvider
                }
                val feed = rememberSearchFeed(
                    state, state.relatedPosts, fab, FeedMarks(liked = liked), durations = FeedDurations(durationStates, resolveInBackground = true),
                )
                val entries = feed.related.projection.entries
                SideEffect { shown = entries.map { (it as RelatedFeedEntry.PostEntry).post.id.sourcePostId } }
                if (withGrid) {
                    grid = rememberLazyStaggeredGridState()
                    LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(1), Modifier.height(300.dp), grid) {
                        items(entries.size) { BasicText("Post $it", Modifier.height(100.dp)) }
                    }
                    rememberSearchScrollAndPaging(state, feed, grid, resolveUnknownAnimatedDurations = true) { action ->
                        actions += action
                        if (action is SearchAction.ScrollRestorationApplied) {
                            state = state.copy(restoration = SearchRestorationUiState.Restored(false))
                        }
                    }
                }
            }
        }
        // Settle the first composition and its background work, so each test starts from a feed at rest.
        frame()
        background.drain()
        frame()
    }

    /**
     * One frame: what the grid draws (null while the Viewer covers the feed, empty for Search's "no results") and how
     * many visibility filter passes ran inside the frame, that is on the main thread.
     */
    private data class Frame(val shown: List<String>?, val filterPasses: Int)

    private fun frame(): Frame {
        val before = filterPasses()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        return Frame(shown, filterPasses() - before)
    }

    /** Runs the queued background work and returns how many visibility filter passes it held. */
    private fun drain(): Int {
        val before = filterPasses()
        background.drain()
        return filterPasses() - before
    }

    private fun filterPasses() = ShadowTrace.getPreviousSections().count { it == FeedTraceSections.FILTER }

    private fun lengthFilterOn() {
        fab = fab.withSearchAnimationFilter(state.query.draft.mode) { it.copy(durationMaxBucket = 2) }
    }

    private fun known(post: Post, ms: Long) =
        mediaDurationKey(post) to MediaDurationState.Known(ms, MediaDurationProvenance.CONTAINER_PROBE)

    private fun ids(range: IntRange) = range.map { "v$it" }

    private fun searchState(results: List<Post>) = SearchUiState(
        content = SearchContentUiState(results = results, hasExecutedSearch = true, canLoadMore = true),
        restoration = SearchRestorationUiState.Restored(false),
    )

    private companion object {
        const val SHORT_MS = 4_000L // inside the 0-10 s range the length filter tests use
        const val LONG_MS = 30_000L
    }
}
