package com.theoriacodex.app.journeys

import com.theoriacodex.app.library.LibrarySection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchJourneyTest : AppJourneyFixture() {
    @Test
    fun namedGroupedMultiSearchSurvivesClearingHistoryAndColdGraphThenFeedsExactSources() {
        val participating = listOf(SourceKey.GELBOORU, SourceKey.PIXIV)
        val query = Query(QueryMode.Unified, emptyList<String>(), listOf("red"), SortMode.POPULAR, null, null)
            .withIncludeTermGroups(listOf(
                SearchTermGroup.single(SearchTerm("landscape")),
                SearchTermGroup(listOf(SearchTerm("blue"), SearchTerm("green"))),
            ))
        io {
            container.data.recentsRepository.recordSearch(query, "pin-journey", RecentSearchKind.MULTI_SEARCH, participating)
        }
        launch()
        library(LibrarySection.History)
        openRecentsSection("Searches")
        compose.onNodeWithTag("Recent search:pin-journey")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        activate(compose.onNodeWithText("Save search"))
        waitFor("Rename action appears on the saved search") { compose.onAllNodesWithText("Rename").fetchSemanticsNodes().isNotEmpty() }
        activate(compose.onNodeWithText("Rename"))
        compose.onNode(hasSetTextAction()).performTextReplacement("My landscapes")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("saved search is named My landscapes") {
            io { container.data.savedSearches.observeSavedSearches().first().singleOrNull()?.name == "My landscapes" }
        }
        val savedSearch = io { container.data.savedSearches.observeSavedSearches().first().single() }
        compose.onNodeWithContentDescription("History actions").performClick()
        compose.onNodeWithText("Clear all History").performClick()
        waitFor("History has no recent searches after Clear all") { io { container.data.recentsRepository.observeSearches().first().isEmpty() } }
        coldGraphRelaunch()
        assertEquals(listOf(savedSearch), io { container.data.savedSearches.observeSavedSearches().first() })
        assertTrue(io { container.data.recentsRepository.observeSearches().first().isEmpty() })
        waitFor("the launch new-posts check finishes", timeoutMillis = 15_000) {
            io { savedSearch.id in container.data.uiRestoreRepository.observeSavedSearchNewPosts().first().marks }
        }
        container.registry.requests.clear()
        library(LibrarySection.Saved)
        activate(compose.onNodeWithText("Searches"))
        waitFor("at least two first-page requests run newest first") { container.registry.requests.count { it.pageToken == null && it.query.sort == SortMode.NEWEST } >= 2 }
        val requests = container.registry.requests.filter { it.pageToken == null }
        assertEquals(participating.toSet(), requests.filter { it.query.sort == SortMode.NEWEST }.map { it.source }.toSet())
        assertTrue(requests.all {
            it.query.effectiveIncludeTermGroups == query.effectiveIncludeTermGroups && it.query.excludeTags == query.excludeTags
        })
        assertTrue(io { container.data.queryRepository.observeAppliedQuery("unified").first() == null })
    }
}
