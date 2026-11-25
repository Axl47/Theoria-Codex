package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import com.theoriacodex.app.library.LibrarySection
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSearchEditJourneyTest : AppJourneyFixture() {
    @Test
    fun editingSavedSearchTagsUpdatesItInPlaceAndLibraryRunsTheNewQuery() {
        val source = SourceKey.PIXIV
        fun search(vararg tags: String) =
            RecentSearchEntry(Query(QueryMode.Source(source), tags.toList(), emptyList(), SortMode.NEWEST, null, null), tags.joinToString(), 1)
        val evening = io {
            container.data.savedSearches.save("Morning", search("multi"))
            container.data.savedSearches.save("Evening", search("landscape"))
        }
        launch()
        editFromLibrary("Evening")
        addSearchTag("green")
        closeComposer()
        compose.onNodeWithText("Update “Evening”").assertDoesNotExist()
        assertEquals(listOf("landscape"), io { container.data.savedSearches.observeSavedSearches().first().first().search.query.includeTags })
        editFromLibrary("Evening")
        addSearchTag("blue")
        waitFor("the edited search runs") {
            container.registry.requests.any { it.source == source && it.query.includeTags == listOf("landscape", "blue") }
        }
        activate(compose.onNodeWithText("Update “Evening”"))
        waitFor("Evening holds the edited tags") {
            io { container.data.savedSearches.observeSavedSearches().first().first().search.query.includeTags == listOf("landscape", "blue") }
        }
        val saved = io { container.data.savedSearches.observeSavedSearches().first() }
        assertEquals(listOf("Evening", "Morning"), saved.map { it.name })
        assertEquals(evening.id, saved.first().id)
        closeComposer()
        container.registry.requests.clear()
        library(LibrarySection.Saved)
        activate(compose.onNodeWithText("Searches"))
        waitFor("the Searches collection runs Evening's new query") {
            container.registry.requests.any {
                it.pageToken == null && it.source == source && it.query.includeTags == listOf("landscape", "blue")
            }
        }
    }

    private fun closeComposer() {
        val open = { compose.onAllNodesWithTag("Search composer").fetchSemanticsNodes().isNotEmpty() }
        repeat(3) {
            if (open()) {
                pressSystemBack()
                compose.waitForIdle()
            }
        }
        waitFor("the composer closes") { !open() }
    }

    private fun editFromLibrary(name: String) {
        library(LibrarySection.Saved)
        waitFor("Searches collection is listed") { compose.onAllNodesWithText("Searches").fetchSemanticsNodes().isNotEmpty() }
        longPress(compose.onNode(hasText("Searches") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)))
        activate(compose.onNodeWithContentDescription("Rename $name"))
        activate(compose.onNodeWithText("Edit search"))
        waitFor("Search opens editing $name") { compose.onNodeWithText("Update “$name”").isDisplayed() }
    }
}
