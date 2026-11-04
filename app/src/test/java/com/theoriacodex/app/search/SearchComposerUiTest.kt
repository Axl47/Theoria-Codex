package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSuggestionsUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchComposerUiTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<SearchAction>()
    private val sketch = SearchTerm("sketch")
    private val groups = listOf(
        SearchTermGroup.single(SearchTerm("mika", SearchFacet.ARTIST)),
        SearchTermGroup(listOf(SearchTerm("rin", SearchFacet.CHARACTER), SearchTerm("aoi", SearchFacet.CHARACTER))),
    )

    private fun open(
        source: SourceKey,
        scopes: List<FacetedSearchScope>,
        saved: List<SavedSearchEntry> = emptyList(),
        onOpenSaved: (SavedSearchEntry) -> Unit = {},
        suggestions: SearchSuggestionsUiState = SearchUiState().suggestions,
        onOpenFavorites: () -> Unit = {},
    ) {
        val base = SearchUiState()
        val draft = emptySearchQuery(QueryMode.Source(source)).copy(
            includeTermGroups = groups,
            excludeTerms = listOf(sketch),
        )
        val state = base.copy(query = base.query.copy(draft = draft, supportedScopes = scopes), suggestions = suggestions)
        compose.setContent {
            MaterialTheme {
                SearchComposer(
                    state = state, onAction = { actions += it }, sourcePicker = {}, onApply = {}, onReset = {},
                    onUndo = {}, onOpenFavorites = onOpenFavorites, onOpenSourceAccounts = {}, onDigitsCommitted = {}, onDismiss = {},
                    savedSearches = saved, onOpenSavedSearch = onOpenSaved,
                )
            }
        }
    }

    @Test fun `tapping a quick saved search dispatches that entry`() {
        val entry = SavedSearchEntry(
            "id", "My landscapes",
            RecentSearchEntry(emptySearchQuery(), "hash", 1L),
            1L,
        )
        var opened: SavedSearchEntry? = null
        open(SourceKey.GELBOORU, emptyList(), listOf(entry), onOpenSaved = { opened = it })
        compose.onNodeWithText("Saved").assertExists()
        compose.onNodeWithText("My landscapes").performClick()
        compose.runOnIdle { assertEquals(entry, opened) }
    }

    @Test fun `the query keeps draft order with type labels and a visible group`() {
        open(SourceKey.HITOMI, listOf(FacetedSearchScope(SearchFacet.SERIES)))
        compose.onNodeWithText("Artist mika").assertExists()
        compose.onNodeWithText("Character rin").assertExists()
        compose.onNodeWithText("+ or").assertExists()
        compose.onNodeWithText("Artists").assertDoesNotExist()
        val required = compose.onNodeWithText("Artist mika").getUnclippedBoundsInRoot().top
        val excluded = compose.onNodeWithText("− sketch").getUnclippedBoundsInRoot().top
        assertTrue(required < excluded)
    }

    @Test fun `tapping a tag opens its actions and removing it can be undone`() {
        open(SourceKey.GELBOORU, emptyList())
        compose.onNodeWithText("− sketch").performClick()
        compose.runOnIdle { assertTrue(actions.none { it is SearchAction.RemoveExcludeTerm }) }
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(SearchAction.RemoveExcludeTerm(sketch), actions.last()) }
        compose.onNodeWithText("Removed sketch").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle {
            assertEquals(SearchAction.RestoreDraftTerms(groups, listOf(sketch)), actions.last())
        }
    }

    @Test fun `a grouped tag offers group actions and a single tag does not`() {
        open(SourceKey.GELBOORU, emptyList())
        compose.onNodeWithText("Character rin").performClick()
        compose.onNodeWithText("Make required on its own").assertExists()
        compose.onNodeWithText("Remove the whole “or” group").performClick()
        compose.runOnIdle { assertEquals(SearchAction.RemoveIncludeGroup(1), actions.last()) }
        compose.onNodeWithText("Artist mika").performClick()
        compose.onNodeWithText("Add an alternative").assertExists()
        compose.onNodeWithText("Make required on its own").assertDoesNotExist()
    }

    @Test fun `plus or targets the group in place until dismissed and the button flips required and excluded`() {
        open(SourceKey.GELBOORU, emptyList())
        val target = compose.onNodeWithTag("Search add target")
        target.assertTextEquals("+ and")
        compose.onNodeWithText("+ or").performClick()
        target.assertTextEquals("or ✕")
        compose.onNodeWithText("type below").assertExists()
        compose.onNodeWithText("Add an alternative").assertExists()
        target.performClick()
        target.assertTextEquals("+ and")
        compose.onNodeWithText("type below").assertDoesNotExist()
        target.performClick()
        target.assertTextEquals("− not")
        compose.onNodeWithText("Exclude a tag").assertExists()
    }

    @Test fun `the query stays on screen with suggestions and a hidden match is explained`() {
        open(
            SourceKey.GELBOORU, emptyList(),
            suggestions = SearchUiState().suggestions.copy(
                input = "ri",
                autocomplete = listOf(TagSuggestion("rivers", type = null, count = null)),
            ),
        )
        compose.onNodeWithText("Artist mika").assertExists()
        compose.onNodeWithText("rivers").assertExists()
        compose.onNodeWithText("Artist mika").performClick()
        compose.onNodeWithText("Add an alternative").performClick()
        compose.onNodeWithText("1 match hidden: this group only takes Artists.").assertExists()
        compose.onNodeWithText("rivers").assertDoesNotExist()
    }

    @Config(qualifiers = "w400dp-h200dp")
    @Test fun `suggestions keep usable room on a short screen`() {
        open(
            SourceKey.GELBOORU, emptyList(),
            suggestions = SearchUiState().suggestions.copy(
                input = "ri",
                autocomplete = listOf(TagSuggestion("rivers", type = null, count = null)),
            ),
        )
        compose.onNodeWithText("rivers").assertIsDisplayed()
    }

    @Test fun `less frequent actions wait in the overflow menu`() {
        var favorites = false
        open(SourceKey.GELBOORU, emptyList(), onOpenFavorites = { favorites = true })
        compose.onNodeWithText("Favorites").assertDoesNotExist()
        compose.onNodeWithContentDescription("More search actions").performClick()
        compose.onNodeWithText("Undo last search").assertIsNotEnabled()
        compose.onNodeWithText("Favorites").performClick()
        compose.runOnIdle { assertTrue(favorites) }
    }
}
