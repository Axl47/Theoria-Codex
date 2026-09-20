package com.theoriacodex.app.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
        activate(compose.onNodeWithText("+ Create"))
        compose.onNodeWithText("Name").performTextInput("Second collection")
        activate(compose.onNodeWithText("Save"))
        compose.waitUntil(10_000) { io { container.content.observeCodices().first().any { it.name == "Second collection" } } }
        activate(compose.onNodeWithContentDescription("Actions for Journey collection"))
        activate(compose.onNodeWithContentDescription("Rename codex"))
        compose.onNodeWithText("Name").performTextReplacement("Renamed collection")
        activate(compose.onNodeWithText("Save"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Renamed collection").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Renamed collection", io { container.content.observeCodex(collection.codexId).first()?.name })
        activate(compose.onNodeWithText("Renamed collection"))
        activate(compose.onNodeWithText("Edit"))
        saved.take(2).forEach { post -> activate(compose.onNodeWithTag(searchCardTestTag(post.id))) }
        activate(compose.onNodeWithContentDescription("Remove 2 selected posts from this Codex"))
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().size == 1 } }
        assertEquals(listOf(saved.last().id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
        compose.onNodeWithText("2 posts removed").assertIsDisplayed()
        activate(compose.onNodeWithText("Undo"))
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().size == 3 } }
        assertEquals(original, io { container.content.observeCodexItems(collection.codexId).first() })
        activate(compose.onNodeWithContentDescription("Back"))
        activate(compose.onNodeWithContentDescription("Actions for Renamed collection"))
        activate(compose.onNodeWithContentDescription("Delete codex"))
        compose.onNodeWithText("Delete Codex?").assertIsDisplayed()
        activate(compose.onNodeWithText("Cancel"))
        assertTrue(io { container.content.observeCodex(collection.codexId).first() != null })
        assertEquals(original, io { container.content.observeCodexItems(collection.codexId).first() })
    }
}
