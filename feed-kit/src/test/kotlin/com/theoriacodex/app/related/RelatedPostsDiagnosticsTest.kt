package com.theoriacodex.app.related

import android.app.Application
import com.theoriacodex.app.testing.testPost
import org.junit.Assert.assertEquals
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
        val shown = testPost(sourcePostId = "shown-9001", canonicalTags = listOf("shown_tag"), authorName = "shown_artist")
        val ancestor = testPost(sourcePostId = "ancestor-5150")
        val request = RelatedPostsRequest(seed, anchorCanonicalIndex = 0, generation = 3, ancestorSeedPostIds = setOf(ancestor.id))
        val retained = listOf(RelatedPostsPage(request, listOf(shown)))
        val states = listOf(
            RelatedPostsUiState.Loading(request, retained),
            RelatedPostsUiState.Loaded(request, listOf(shown)),
            RelatedPostsUiState.Empty(request, retained),
            RelatedPostsUiState.Failed(request, "GET https://example.com/u/private_artist failed for user@example.com", retained),
        )
        val diagnostics = RelatedPostsDiagnostics("search", Any())
        ShadowLog.clear()

        states.forEach { state -> diagnostics.record("event", state) }

        val lines = ShadowLog.getLogsForTag("RelatedPosts").map { it.msg }
        assertEquals(states.size, lines.size)
        lines.forEach { line ->
            listOf(
                "seed-4711", "shown-9001", "ancestor-5150", "secret_tag", "shown_tag", "landscape",
                "example.com", "user@example.com", "private_artist", "shown_artist", "failed for",
            ).forEach { secret -> assertFalse(line, secret in line) }
        }
    }
}
