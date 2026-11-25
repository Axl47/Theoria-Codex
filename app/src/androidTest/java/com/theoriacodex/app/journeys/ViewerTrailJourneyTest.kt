package com.theoriacodex.app.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerTrailJourneyTest : AppJourneyFixture() {
    @Test
    fun theDockSavesInOneTapAndATagForkReturnsToTheOriginalPost() {
        val original = posts.getValue(SourceKey.PIXIV)[ORIGINAL_POST]
        val codex = io { container.content.ensureCodex(profileScopedCodexId("profile-main", "trail"), "Trail collection") }
        openSearchPost(ORIGINAL_POST, RECENT_SEARCH)
        waitFor("Viewer shows post ${original.id.sourcePostId}") { selectedPost() == original.id.sourcePostId }

        val save = compose.onNodeWithTag("viewer-dock-save")
        waitFor("Viewer save button offers Save to Trail collection") { save.isDisplayed() && save.description() == "Save to Trail collection" }
        activate(save)
        waitFor("original post is saved to the Trail collection") { io { container.content.observeCodexItems(codex.codexId).first() }.any { it.postId == original.id } }
        waitFor("Viewer save button shows Saved to Trail collection", timeoutMillis = 5_000) { save.description()?.startsWith("Saved to Trail collection") == true }

        activate(compose.onNodeWithTag("viewer-trail-chip-tag:green"))
        val forkCrumb = compose.onNodeWithTag("viewer-trail-crumb-1")
        waitFor("fork crumb is displayed", timeoutMillis = 15_000) { forkCrumb.isDisplayed() }
        val forkStart = selectedPost()

        compose.onNodeWithContentDescription("Media actions", useUnmergedTree = true).performTouchInput {
            swipe(start = Offset(width * 0.85f, height * 0.5f), end = Offset(width * 0.15f, height * 0.5f), durationMillis = 400L)
        }
        waitFor("Viewer moved to another post in the fork") { selectedPost().let { it != null && it != forkStart } }

        activate(compose.onNodeWithContentDescription("Back"))
        waitFor("Viewer is back on the original post without the fork crumb") { !forkCrumb.isDisplayed() && selectedPost() == original.id.sourcePostId }
    }

    @Test
    fun anAddedTagBecomesAStepAndRemovingAnEarlierStepReRunsIt() {
        openSearchPost(ORIGINAL_POST, ADD_REMOVE_SEARCH, tags = listOf("landscape"))
        waitFor("Viewer shows the original post") { selectedPost() == posts.getValue(SourceKey.PIXIV)[ORIGINAL_POST].id.sourcePostId }

        activate(compose.onNodeWithTag("viewer-trail-chip-tag:green"))
        waitFor("green step is on the path", timeoutMillis = 15_000) { crumb(1) == "green" }

        addTag("pixiv", "pixiv_seed")
        waitFor("pixiv seed step is on the path", timeoutMillis = 15_000) { crumb(2) == "pixiv seed" }
        addTag("mul", "multi")
        waitFor("multi step is on the path", timeoutMillis = 15_000) { crumb(3) == "multi" }

        val green = compose.onNodeWithTag("viewer-trail-crumb-1")
        waitFor("green crumb is displayed") { green.isDisplayed() }
        green.performTouchInput { longClick() }
        activate(compose.onNodeWithText("Remove from trail"))
        waitFor("the path re-ran without green", timeoutMillis = 20_000) { crumb(1) == "pixiv seed" && crumb(2) == "multi" && crumb(3) == null }

        val rerun = container.registry.requests.last { it.pageToken == null }.query.includeTags
        assertEquals(setOf("multi", "pixiv_seed", "landscape"), rerun.toSet())
    }

    private fun addTag(typed: String, tag: String) {
        compose.onNodeWithTag("viewer-trail-row").performScrollToKey("add")
        activate(compose.onNodeWithContentDescription("Add tag"))
        compose.onNodeWithTag("trail-tag-field").performTextInput(typed)
        activate(compose.onNodeWithTag("trail-tag-suggestion-$tag"))
    }

    private fun crumb(index: Int): String? = compose.onAllNodes(hasTestTag("viewer-trail-crumb-$index")).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

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
        const val ADD_REMOVE_SEARCH = "journey:trail-edit"
    }
}
