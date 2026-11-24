package com.theoriacodex.app.codex

import com.theoriacodex.app.ui.components.FeedDurations
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
import com.theoriacodex.app.testing.SteppedDispatcher
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.app.ui.components.LocalFeedComputationContext
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CodexFeedFramesTest {
    @get:Rule val compose = createComposeRule()

    private val background = SteppedDispatcher()

    private val videos = List(6) { testPost(SourceKey.RULE34VIDEO, "v$it") }
    private val stills = List(3) { testPost(sourcePostId = "s$it") }
    private val lengths = mapOf("v0" to 3_000L, "v1" to 9_000L, "v2" to 1_000L, "v3" to 7_000L, "v4" to 5_000L, "v5" to 2_000L)
    private var codexId by mutableStateOf("a")
    private var stored by mutableStateOf(videos + stills)
    private var fab by mutableStateOf(FeedFabRestoreState())
    private var durations by mutableStateOf(states(videos))
    private val durationStates by derivedStateOf { mediaDurationStates(stored, durations) }
    private var feedShown by mutableStateOf(true)
    private var sort by mutableStateOf(CodexSortMode.LONGEST)
    private var shown: List<String>? = null

    private val longest = listOf("v1", "v3", "v4", "v0", "v5", "v2", "s0", "s1", "s2")

    @Test fun `returning from the Viewer redoes neither the known lengths, the sort nor the filter`() {
        show()
        openViewer()

        feedShown = true
        assertEquals(Frame(longest), frame())
        assertEquals(Frame(longest), drainedFrame())
    }

    @Test fun `returning with a Codex filter on reuses the kept filter result`() {
        fab = fab.copy(animatedOnly = true)
        show()
        openViewer()

        feedShown = true
        assertEquals(Frame(longest - stills.ids()), frame())
        assertEquals(Frame(longest - stills.ids()), drainedFrame())
    }

    @Test fun `a filter changed while the Viewer was open is applied in place on the first frame back`() {
        show()
        openViewer()
        fab = fab.copy(animatedOnly = true)

        feedShown = true
        assertEquals(Frame(longest - stills.ids(), filter = 1), frame())
        assertEquals(Frame(longest - stills.ids()), drainedFrame())
    }

    @Test fun `a length that resolved while the Viewer was open sorts in place on the first frame back`() {
        durations = states(videos - videos[1])
        show()
        assertEquals(listOf("v3", "v4", "v0", "v5", "v2", "v1", "s0", "s1", "s2"), shown)
        openViewer()
        durations = states(videos)

        feedShown = true
        assertEquals(Frame(longest, knownLengths = 1, sort = 1, filter = 1), frame())
        assertEquals(Frame(longest), drainedFrame())
    }

    @Test fun `posts saved while the Viewer was open are sorted in place on the first frame back`() {
        val added = testPost(SourceKey.RULE34VIDEO, "v6")
        show()
        openViewer()
        stored = listOf(added) + stored
        durations = durations + (mediaDurationKey(added) to known(20_000L))

        feedShown = true
        assertEquals(Frame(listOf("v6") + longest, knownLengths = 1, sort = 1, filter = 1), frame())
        assertEquals(Frame(listOf("v6") + longest), drainedFrame())
    }

    @Test fun `a filter change on screen keeps the previous result until its one background pass`() {
        stored = stills
        show()
        assertEquals(Frame(stills.map { it.id.sourcePostId }), frame())

        fab = fab.copy(animatedOnly = true)
        assertEquals(Frame(listOf("s0", "s1", "s2")), frame())
        assertEquals(1, drainedPasses().filter)
        assertEquals(Frame(emptyList()), frame())

        fab = fab.copy(animatedOnly = false)
        assertEquals(Frame(emptyList()), frame())
        assertEquals(1, drainedPasses().filter)
        assertEquals(Frame(listOf("s0", "s1", "s2")), frame())
    }

    @Test fun `another Codex in the same entry never shows the first one's posts, and the first keeps its own result`() {
        show()
        openViewer()
        codexId = "b"
        stored = stills.reversed()

        feedShown = true
        assertEquals(Frame(listOf("s2", "s1", "s0"), knownLengths = 1, sort = 1, filter = 1), frame())
        openViewer()
        codexId = "a"
        stored = videos + stills

        feedShown = true
        assertEquals(Frame(longest, knownLengths = 1, sort = 1, filter = 1), frame())
    }

    @Test fun `another Codex holding the very same list object still gets its own filter`() {
        fab = fab.copy(animatedOnly = true)
        show()
        openViewer()
        codexId = "b"
        fab = FeedFabRestoreState()

        feedShown = true
        assertEquals(Frame(longest, knownLengths = 1, sort = 1, filter = 1), frame())
    }

    @Test fun `with the length filter on, an arriving length that reveals nothing costs no filter pass`() {
        lengthFilterOn(knownMs = mapOf("v0" to SHORT_MS))
        show()
        assertEquals(Frame(listOf("v0")), frame())

        durations = durations + (mediaDurationKey(videos[4]) to known(SHORT_MS))
        assertEquals(Frame(listOf("v0")), frame())
        assertEquals(0, drainedPasses().filter)
        assertEquals(Frame(listOf("v0")), frame())
        assertEquals(0, drainedPasses().filter)
        assertEquals(Frame(listOf("v0")), frame())
    }

    @Test fun `with the length filter on, each revealing length is filtered in place and equals a whole pass`() {
        lengthFilterOn(knownMs = emptyMap())
        show()
        val arriving = videos.mapIndexed { index, video -> video to if (index % 3 == 1) LONG_MS else SHORT_MS }
        val knownMs = mutableMapOf<PostId, Long>()
        arriving.forEachIndexed { index, (video, ms) ->
            durations = durations + (mediaDurationKey(video) to known(ms))
            knownMs[video.id] = ms
            val before = shown
            assertEquals(Frame(before), frame())
            assertEquals(0, drainedPasses().filter)
            val whole = filterCodexCollectionPosts(
                videos.take(index + 1), fab.savedCollectionFilters(), knownMs,
            ).map { it.id.sourcePostId }
            assertEquals(Frame(whole, filter = 1), frame())
            assertEquals(Frame(whole), drainedFrame())
        }
        assertEquals(listOf("v0", "v2", "v3", "v5"), shown)
    }

    private fun lengthFilterOn(knownMs: Map<String, Long>) {
        sort = CodexSortMode.NEWEST_SAVED
        stored = videos
        fab = fab.copy(durationMaxBucket = 2)
        durations = videos.filter { it.id.sourcePostId in knownMs }
            .associate { mediaDurationKey(it) to known(knownMs.getValue(it.id.sourcePostId)) }
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalFeedComputationContext provides background) {
                if (!feedShown) {
                    SideEffect { shown = null }
                    return@CompositionLocalProvider
                }
                val sorted = rememberSortedCodexPosts(codexId, stored, sort, emptyMap(), durationStates)
                val feed = rememberCodexDetailFeed(
                    codexId, sorted.orEmpty(), fab.savedCollectionFilters(),
                    FeedDurations(durationStates, resolveInBackground = true), relatedShelf = null, sortMode = sort,
                )
                SideEffect { shown = feed.visiblePosts.map { it.id.sourcePostId } }
            }
        }
        frame()
        background.drain()
        frame()
    }

    private fun openViewer() {
        feedShown = false
        assertEquals(Frame(null), frame())
    }

    private data class Frame(val shown: List<String>?, val knownLengths: Int = 0, val sort: Int = 0, val filter: Int = 0)

    private fun frame(): Frame {
        val before = passes()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        val after = passes()
        return Frame(shown, after.knownLengths - before.knownLengths, after.sort - before.sort, after.filter - before.filter)
    }

    private fun drainedFrame(): Frame {
        val before = passes()
        background.drain()
        assertEquals(before, passes())
        return frame()
    }

    private fun drainedPasses(): Frame {
        val before = passes()
        background.drain()
        val after = passes()
        return Frame(null, after.knownLengths - before.knownLengths, after.sort - before.sort, after.filter - before.filter)
    }

    private fun passes(): Frame {
        val sections = ShadowTrace.getPreviousSections()
        return Frame(
            null,
            sections.count { it == FeedTraceSections.KNOWN_DURATIONS },
            sections.count { it == FeedTraceSections.SORT },
            sections.count { it == FeedTraceSections.FILTER },
        )
    }

    private fun states(posts: List<Post>): Map<MediaDurationKey, MediaDurationState> =
        posts.associate { mediaDurationKey(it) to known(lengths.getValue(it.id.sourcePostId)) }

    private fun known(ms: Long) = MediaDurationState.Known(ms, MediaDurationProvenance.CONTAINER_PROBE)

    private fun List<Post>.ids() = map { it.id.sourcePostId }.toSet()

    private companion object {
        const val SHORT_MS = 4_000L
        const val LONG_MS = 30_000L
    }
}
