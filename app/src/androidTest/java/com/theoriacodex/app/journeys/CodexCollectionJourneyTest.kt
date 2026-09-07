package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCollectionJourneyTest : AppJourneyFixture() {
    @Test
    fun collectionActionsRenameAndBulkRemovalUndoReachDurableMemberships() {
        val saved = posts.getValue(SourceKey.PIXIV).take(3)
        val collection = io {
            container.content.ensureCodex(profileScopedCodexId("profile-main", "journey"), "Journey collection")
                .also { container.content.addItems(it.codexId, saved) }
        }
        val original = io { container.content.observeCodexItems(collection.codexId).first() }
        launch()
        tab("Codex")
        compose.onNodeWithText("+ Create").performClick()
        compose.onNodeWithText("Name").performTextInput("Second collection")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { io { container.content.observeCodices().first().any { it.name == "Second collection" } } }
        compose.onNodeWithContentDescription("Actions for Journey collection").performClick()
        compose.onNodeWithContentDescription("Rename codex").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Renamed collection")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Renamed collection").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Renamed collection", io { container.content.observeCodex(collection.codexId).first()?.name })
        compose.onNodeWithText("Renamed collection").performClick()
        compose.onNodeWithText("Edit").performClick()
        saved.take(2).forEach { post -> compose.onNodeWithTag(searchCardTestTag(post.id)).performClick() }
        compose.onNodeWithContentDescription("Remove 2 selected posts from this Codex").performClick()
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().size == 1 } }
        assertEquals(listOf(saved.last().id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
        compose.onNodeWithText("2 posts removed").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().size == 3 } }
        assertEquals(original, io { container.content.observeCodexItems(collection.codexId).first() })
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithText("Renamed collection").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Delete codex").performClick()
        compose.onNodeWithText("Delete Codex?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(io { container.content.observeCodex(collection.codexId).first() != null })
        assertEquals(original, io { container.content.observeCodexItems(collection.codexId).first() })
    }
}
