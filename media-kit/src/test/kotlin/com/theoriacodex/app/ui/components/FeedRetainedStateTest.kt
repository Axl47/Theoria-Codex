package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.media.DurationFilterProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedRetainedStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a feed's grid state is the same object when the feed comes back into composition (ADR 0106)`() {
        var shown by mutableStateOf(true)
        val states = mutableListOf<LazyStaggeredGridState>()
        val projections = mutableListOf<DurationFilterProjection>()
        compose.setContent {
            if (shown) {
                states += rememberRetainedFeedGridState("feed")
                projections += rememberRetainedDurationProjection("feed")
            }
        }
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()

        assertEquals(1, states.toSet().size)
        assertSame(states.first(), states.last())
        assertSame(projections.first(), projections.last())
    }

    @Test fun `a feed dropped from the kept set is forgotten while the effect stays in composition (ADR 0106)`() {
        var keep by mutableStateOf(setOf("feed:a", "feed:b"))
        lateinit var retained: FeedRetainedState
        compose.setContent {
            retained = viewModel()
            ForgetRetainedFeedsEffect("feed:", keep)
        }
        compose.waitForIdle()
        val dropped = retained.durationProjection("feed:b")
        val kept = retained.durationProjection("feed:a")

        keep = setOf("feed:a")
        compose.waitForIdle()

        assertNotSame(dropped, retained.durationProjection("feed:b"))
        assertSame(kept, retained.durationProjection("feed:a"))
    }

    @Test fun `forgetting keeps the kept feeds and their parts in every retained map, and leaves keys outside the prefix alone (ADR 0106)`() {
        val retained = FeedRetainedState()
        val keys = listOf("for_you:mix:1", "for_you:mix:1:related", "for_you:mix:10", "for_you:trail:2", "search")
        val frames = RetainedFrames<String, String>()
        fun snapshot() = keys.associateWith { key ->
            listOf(retained.gridState(key) { LazyStaggeredGridState() }, retained.durationProjection(key), retained.frameCache(frames.at(key)))
        }
        val before = snapshot()

        retained.forgetExcept("for_you:", setOf("for_you:mix:1"))

        val after = snapshot()
        listOf("for_you:mix:1", "for_you:mix:1:related", "search").forEach { key ->
            before.getValue(key).zip(after.getValue(key)).forEach { (old, new) -> assertSame(old, new) }
        }
        listOf("for_you:mix:10", "for_you:trail:2").forEach { key ->
            before.getValue(key).zip(after.getValue(key)).forEach { (old, new) -> assertNotSame(old, new) }
        }
    }

    @Test fun `a retained frame cache is shared only by the frames that created it (ADR 0106)`() {
        val retained = FeedRetainedState()
        val counts = RetainedFrames<String, Int>()
        val labels = RetainedFrames<String, String>()
        val first = retained.frameCache(counts.at("feed"))

        assertSame(first, retained.frameCache(counts.at("feed")))
        assertNotSame(first, retained.frameCache(labels.at("feed")))
        assertNotSame(first, retained.frameCache(counts.at("feed")))
    }
}
