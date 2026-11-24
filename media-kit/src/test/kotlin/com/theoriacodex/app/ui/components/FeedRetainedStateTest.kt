package com.theoriacodex.app.ui.components

import android.app.Application
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        compose.setContent { if (shown) states += rememberRetainedFeedGridState("feed") }
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()

        assertEquals(1, states.toSet().size)
        assertSame(states.first(), states.last())
    }

    @Test fun `forgetting keeps the kept feeds and their parts in every retained map, and leaves keys outside the prefix alone (ADR 0106)`() {
        val retained = FeedRetainedState()
        val keys = listOf("for_you:mix:1", "for_you:mix:1:related", "for_you:mix:10", "for_you:trail:2", "search")
        fun snapshot() = keys.associateWith { key ->
            listOf(retained.gridState(key) { LazyStaggeredGridState() }, retained.durationProjection(key), retained.frameCache<String, String>(key))
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
}
