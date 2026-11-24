package com.theoriacodex.app.related

import android.app.Application
import com.theoriacodex.app.testing.testPost
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RelatedPostsDiagnosticsTest {
    @Test
    fun `a lifecycle line names no post, tag, address or account (ADR 0077)`() {
        val seed = testPost(sourcePostId = "seed-4711", canonicalTags = listOf("secret_tag"), authorName = "private_artist")
        val state = RelatedPostsUiState.Loaded(
            RelatedPostsRequest(seed, anchorCanonicalIndex = 0, generation = 3),
            listOf(testPost(sourcePostId = "shown-9001")),
        )
        ShadowLog.clear()

        RelatedPostsDiagnostics("search", Any()).record("loaded", state)

        val line = ShadowLog.getLogsForTag("RelatedPosts").single().msg
        listOf("seed-4711", "shown-9001", "secret_tag", "landscape", "example.com", "private_artist").forEach { secret ->
            assertFalse(line, secret in line)
        }
    }
}
