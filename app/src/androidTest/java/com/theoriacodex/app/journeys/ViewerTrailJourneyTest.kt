package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Test

class ViewerTrailJourneyTest : AppJourneyFixture() {
    @Test
    fun theDockSavesInOneTapAndATagForkReturnsToTheOriginalPost() {
        val original = posts.getValue(SourceKey.PIXIV)[ORIGINAL_POST]
        val codex = io { container.content.ensureCodex(profileScopedCodexId("profile-main", "trail"), "Trail collection") }
        openSearchPost(ORIGINAL_POST, RECENT_SEARCH)
        compose.waitUntil(10_000) { selectedPost() == original.id.sourcePostId }

        val save = compose.onNodeWithTag("viewer-dock-save")
        compose.waitUntil(10_000) { save.isDisplayed() && save.description() == "Save to Trail collection" }
        activate(save)
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(codex.codexId).first() }.any { it.postId == original.id } }
        compose.waitUntil(5_000) { save.description()?.startsWith("Saved to Trail collection") == true }

        activate(compose.onNodeWithTag("viewer-trail-chip-tag:landscape"))
        val forkCrumb = compose.onNodeWithTag("viewer-trail-crumb-1")
        compose.waitUntil(15_000) { forkCrumb.isDisplayed() }
        val forkStart = selectedPost()

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { selectedPost().let { it != null && it != forkStart } }

        activate(compose.onNodeWithContentDescription("Back"))
        compose.waitUntil(10_000) { !forkCrumb.isDisplayed() && selectedPost() == original.id.sourcePostId }
    }


    /** The post the Viewer's strip marks as open, by its fixture id. */
    private fun selectedPost(): String? = compose.onAllNodes(isSelected()).fetchSemanticsNodes()
        .firstNotNullOfOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.removePrefix("Open post ")
                ?.takeIf { it.startsWith("journey-") }
        }

    private fun SemanticsNodeInteraction.description(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private companion object {
        const val ORIGINAL_POST = 3
        const val RECENT_SEARCH = "journey:trail"
    }
}
