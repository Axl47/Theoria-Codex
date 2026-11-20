package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationProvenance
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.app.media.mediaDurationStates
import com.theoriacodex.app.related.RelatedPostsRequest
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.state.SearchContentUiState
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.SteppedDispatcher
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.LocalFeedComputationContext
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Search's related shelf shows after every frame, with the length filter on (plan
 * `.docs/exec/shared-feed-presenter.html`). Background work runs only when [background] is drained; removing the feed
 * from composition stands in for opening the Viewer, as navigating to its destination does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RelatedShelfFramesTest {
    @get:Rule val compose = createComposeRule()

    private val background = SteppedDispatcher()

    // Animated posts with no length in their metadata, so the length filter waits for [durations].
    private val feedPosts = List(4) { testPost(SourceKey.RULE34VIDEO, "v$it") }
    private val shelfPosts = List(6) { testPost(SourceKey.RULE34VIDEO, "s$it") }
    private val state = SearchUiState(
        content = SearchContentUiState(results = feedPosts, hasExecutedSearch = true, canLoadMore = true),
        restoration = SearchRestorationUiState.Restored(false),
        relatedPosts = RelatedPostsUiState.Loaded(RelatedPostsRequest(feedPosts[0], 0, 1L), shelfPosts),
    )
    private val fab = FeedFabRestoreState().withSearchAnimationFilter(state.query.draft.mode) { it.copy(durationMaxBucket = 2) }
    private var durations by mutableStateOf(feedPosts.associate { known(it, SHORT_MS) })
    private val durationStates by derivedStateOf { mediaDurationStates(feedPosts + shelfPosts, durations) }
    private var feedShown by mutableStateOf(true)

    /** The shelf posts shown in the last applied composition (null while the Viewer covers the feed). */
    private var shelf: List<String>? = null
    /** The same shelf filtered whole, from the inputs of that composition. */
    private var wholePass: List<String>? = null

    @Test fun `returning from the Viewer shows the shelf as it left on the first frame`() {
        durations = durations + shelfPosts.associate { known(it, SHORT_MS) }
        show()
        assertEquals(ids(0..5), shelf)

        feedShown = false
        frame()
        feedShown = true
        frame()
        assertEquals(ids(0..5), shelf)
        background.drain()
        frame()
        assertEquals(ids(0..5), shelf)
    }

    @Test fun `the shelf equals a whole filter pass after each arriving length and each return`() {
        show()
        shelfPosts.forEachIndexed { index, post ->
            durations = durations + known(post, if (index % 3 == 1) LONG_MS else SHORT_MS)
            frame()
            assertEquals(wholePass, shelf)
            background.drain()
            frame()
            assertEquals(wholePass, shelf)
            if (index % 2 == 0) {
                feedShown = false
                frame()
                feedShown = true
                frame()
                assertEquals(wholePass, shelf)
            }
        }
        assertEquals(listOf("s0", "s2", "s3", "s5"), shelf)
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalFeedComputationContext provides background) {
                if (!feedShown) {
                    SideEffect { shelf = null }
                    return@CompositionLocalProvider
                }
                val feed = rememberSearchFeed(
                    state, fab, emptySet(), pendingLikePostIds = emptySet(), savedPostIds = emptySet(),
                    watchedPostIds = emptySet(), durationStates = durationStates, resolveUnknownAnimatedDurations = true,
                )
                val related = feed.related
                SideEffect {
                    shelf = related.visiblePosts.map { it.id.sourcePostId }
                    wholePass = filterSearchResults(
                        shelfPosts, feed.visibilityFilters, emptySet(), emptySet(),
                        unknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND,
                        knownDurationMsByPostId = related.knownDurationMsByPostId,
                    ).map { it.id.sourcePostId }
                }
            }
        }
        frame()
        background.drain()
        frame()
    }

    private fun frame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun known(post: Post, ms: Long): Pair<MediaDurationKey, MediaDurationState> =
        mediaDurationKey(post) to MediaDurationState.Known(ms, MediaDurationProvenance.CONTAINER_PROBE)

    private fun ids(range: IntRange) = range.map { "s$it" }

    private companion object {
        const val SHORT_MS = 4_000L // inside the 0-10 s range
        const val LONG_MS = 30_000L
    }
}
