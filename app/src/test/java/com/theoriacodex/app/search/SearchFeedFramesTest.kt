package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import com.theoriacodex.app.related.RelatedFeedEntry
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTrace

/**
 * Pins what the Search feed shows after every frame, on today's code (plan `.docs/exec/shared-feed-presenter.html`,
 * Phase 0). Frames advance by hand and background work runs only when [background] is drained, so each step shows
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

    @Test fun `each arriving length with the length filter on refilters the presented list in place`() {
        lengthFilterOn()
        show()
        assertEquals(Frame(emptyList(), filterPasses = 0), frame())

        // One length per step. The projection answers in the background with a new presented list, which the
        // visibility filter then treats as a new list and filters whole, in the frame itself.
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

    @Test fun `returning from the Viewer with the length filter off filters the whole list again on the first frame`() {
        show()
        feedShown = false
        assertEquals(Frame(null, filterPasses = 0), frame())

        feedShown = true
        // The helper's result did not survive the Viewer, so the first frame back filters every loaded post in place.
        assertEquals(Frame(ids(0..7), filterPasses = 1), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7), filterPasses = 0), frame())
    }

    @Test fun `returning from the Viewer with the length filter on shows no results for one frame`() {
        lengthFilterOn()
        durations = posts.take(8).associate { known(it, SHORT_MS) }
        show()
        feedShown = false
        assertEquals(Frame(null, filterPasses = 0), frame())

        feedShown = true
        // Today's behaviour, which Phase 1a removes: the length frame did not survive the Viewer, so the first frame
        // back presents nothing and Search draws "no results" until the background projection answers.
        assertEquals(Frame(emptyList(), filterPasses = 1), frame())
        assertEquals(0, drain())
        assertEquals(Frame(ids(0..7), filterPasses = 1), frame())
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
                    state, fab, liked, pendingLikePostIds = emptySet(), savedPostIds = emptySet(),
                    watchedPostIds = emptySet(), durationStates = durations, resolveUnknownAnimatedDurations = true,
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

    /** Queues background work until [drain] runs it on the test thread. */
    private class SteppedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val pending get() = queue.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private companion object {
        const val SHORT_MS = 4_000L // inside the 0-10 s range the length filter tests use
        const val LONG_MS = 30_000L
    }
}
