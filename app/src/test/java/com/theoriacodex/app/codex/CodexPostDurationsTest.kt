package com.theoriacodex.app.codex

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.theoriacodex.app.media.MediaDurationProvenance
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.mediaDurationKey
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.ui.components.FeedTraceSections
import com.theoriacodex.data.repository.CodexSortMode
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
class CodexPostDurationsTest {
    @get:Rule val compose = createComposeRule()

    private val posts = List(4) { testPost(SourceKey.RULE34VIDEO, "c$it") }
    private val lengths = posts.withIndex().associate { (index, post) -> post.id to 1_000L * (index + 1) }
    private val states = posts.associate { post ->
        mediaDurationKey(post) to MediaDurationState.Known(lengths.getValue(post.id), MediaDurationProvenance.CONTAINER_PROBE)
    }

    @Test fun `a length sort reads known lengths and an appended page adds only its own`() {
        var shown by mutableStateOf(posts.take(2))
        var sort by mutableStateOf(CodexSortMode.LONGEST)
        var durations = emptyMap<PostId, Long>()
        compose.setContent {
            val value = rememberCodexPostDurations(shown, sort, states, codexId = "c")
            SideEffect { durations = value }
        }
        compose.runOnIdle {
            assertEquals(lengths.filterKeys { it in setOf(posts[0].id, posts[1].id) }, durations)
            shown = posts
        }
        compose.runOnIdle {
            assertEquals(lengths, durations)
            // One pass for the first list, one for the appended page.
            assertEquals(2, ShadowTrace.getPreviousSections().count { it == FeedTraceSections.KNOWN_DURATIONS })
            sort = CodexSortMode.NEWEST_SAVED
        }
        compose.runOnIdle { assertEquals(emptyMap<PostId, Long>(), durations) }
    }
}
