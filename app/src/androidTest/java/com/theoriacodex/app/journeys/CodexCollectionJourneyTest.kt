package com.theoriacodex.app.journeys

import com.theoriacodex.app.library.LibrarySection
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.search.searchCardTestTag
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexCollectionJourneyTest : AppJourneyFixture() {
    @Test
    fun renameInPlaceAndBulkRemovalUndoReachDurableMemberships() {
        val collection = savedCollection()
        val original = io { container.content.observeCodexItems(collection.codexId).first() }
        val saved = posts.getValue(SourceKey.PIXIV).take(3)
        launch()
        library(LibrarySection.Codices)
        activate(compose.onNodeWithContentDescription("Create Codex"))
        compose.onNodeWithText("Name").performTextInput("Second collection")
        activate(compose.onNodeWithText("Save"))
        compose.waitUntil(10_000) { io { container.content.observeCodices().first().any { it.name == "Second collection" } } }
        activate(compose.onNodeWithText("Journey collection"))

        activate(compose.onNodeWithText("Journey collection"))
        val name = compose.onNodeWithTag("codex-name-field")
        compose.waitUntil(10_000) { name.isDisplayed() }
        name.performTextReplacement("Renamed collection")
        name.performImeAction()
        compose.waitUntil(10_000) { io { container.content.observeCodex(collection.codexId).first()?.name == "Renamed collection" } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Renamed collection").fetchSemanticsNodes().isNotEmpty() }

        activate(compose.onNodeWithContentDescription("Select posts"))
        saved.take(2).forEach { post -> activate(compose.onNodeWithTag(searchCardTestTag(post.id))) }
        activate(compose.onNodeWithContentDescription("Remove 2 selected posts from this Codex"))
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().size == 1 } }
        assertEquals(listOf(saved.last().id), io { container.content.observeCodexItems(collection.codexId).first().map { it.postId } })
        compose.waitUntil(10_000) { compose.onNodeWithText("2 posts removed").isDisplayed() }
        activate(compose.onNodeWithText("Undo"))
        compose.waitUntil(10_000) { io { container.content.observeCodexItems(collection.codexId).first().size == 3 } }
        assertEquals(original, io { container.content.observeCodexItems(collection.codexId).first() })
    }

    @Test
    fun deletingFromDetailOffersUndoInTheLibraryTileItVacated() {
        val collection = savedCollection()
        val original = io { container.content.observeCodexItems(collection.codexId).first() }
        launch()
        library(LibrarySection.Codices)
        activate(compose.onNodeWithText("Journey collection"))
        activate(compose.onNodeWithContentDescription("More"))
        activate(compose.onNodeWithText("Delete Codex"))
        compose.waitUntil(10_000) { io { container.content.observeCodex(collection.codexId).first() == null } }

        val offer = compose.onNodeWithText("Deleted Journey collection")
        compose.waitUntil(10_000) { offer.isDisplayed() }
        compose.onNodeWithContentDescription("Create Codex").assertExists()
        activate(compose.onNodeWithText("Undo"))
        compose.waitUntil(10_000) { io { container.content.observeCodex(collection.codexId).first() != null } }
        assertEquals(original, io { container.content.observeCodexItems(collection.codexId).first() })
        compose.waitUntil(10_000) { compose.onNodeWithText("Journey collection").isDisplayed() }
    }

    /** A Codex in the main profile holding three Pixiv fixture posts. */
    private fun savedCollection(): Codex = io {
        container.content.ensureCodex(profileScopedCodexId("profile-main", "journey"), "Journey collection")
            .also { container.content.addItems(it.codexId, posts.getValue(SourceKey.PIXIV).take(3)) }
    }
}
