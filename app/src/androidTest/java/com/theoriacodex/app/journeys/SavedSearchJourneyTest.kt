package com.theoriacodex.app.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        tab("Recents")
        openRecentsSection("Searches")
        compose.onNodeWithTag("Recent search:pin-journey")
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNode(hasSetTextAction()).performTextReplacement("My landscapes")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10_000) {
            io { container.data.savedSearches.observeSavedSearches().first().singleOrNull()?.name == "My landscapes" }
        }
        val savedSearch = io { container.data.savedSearches.observeSavedSearches().first().single() }
        compose.onNodeWithContentDescription("Recents actions").performClick()
        compose.onNodeWithText("Clear all Recents").performClick()
        compose.waitUntil(10_000) { io { container.data.recentsRepository.observeSearches().first().isEmpty() } }
        coldGraphRelaunch()
        assertEquals(listOf(savedSearch), io { container.data.savedSearches.observeSavedSearches().first() })
        assertTrue(io { container.data.recentsRepository.observeSearches().first().isEmpty() })
        tab("Codex")
        activate(compose.onNodeWithText("Searches"))
        compose.waitUntil(10_000) { container.registry.requests.count { it.pageToken == null } >= 2 }
        val requests = container.registry.requests.filter { it.pageToken == null }
        assertEquals(participating.toSet(), requests.map { it.source }.toSet())
        assertTrue(requests.all {
            it.query.effectiveIncludeTermGroups == query.effectiveIncludeTermGroups &&
                it.query.excludeTags == query.excludeTags && it.query.sort == query.sort
        })
        assertTrue(io { container.data.queryRepository.observeAppliedQuery("unified").first() == null })
    }
}
