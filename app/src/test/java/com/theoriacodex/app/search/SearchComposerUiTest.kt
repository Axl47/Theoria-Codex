package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.adapter.FacetedSearchScope
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

    private fun open(
        source: SourceKey,
        scopes: List<FacetedSearchScope>,
        saved: List<SavedSearchEntry> = emptyList(),
        onOpenSaved: (SavedSearchEntry) -> Unit = {},
    ) {
        val base = SearchUiState()
        val draft = emptySearchQuery(QueryMode.Source(source)).copy(
            includeTermGroups = listOf(
                SearchTermGroup.single(SearchTerm("mika", SearchFacet.ARTIST)),
                SearchTermGroup(listOf(SearchTerm("rin", SearchFacet.CHARACTER), SearchTerm("aoi", SearchFacet.CHARACTER))),
            ),
            excludeTerms = listOf(sketch),
        )
        val state = base.copy(query = base.query.copy(draft = draft, supportedScopes = scopes))
        compose.setContent {
            MaterialTheme {
                SearchComposer(
                    state = state, onAction = { actions += it }, sourcePicker = {}, onApply = {}, onReset = {},
                    onUndo = {}, onOpenFavorites = {}, onOpenSourceAccounts = {}, onDigitsCommitted = {}, onDismiss = {},
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
        open(SourceKey.GELBOORU, emptyList(), listOf(entry)) { opened = it }
        compose.onNodeWithText("Saved").assertExists()
        compose.onNodeWithText("My landscapes").performClick()
        compose.runOnIdle { assertEquals(entry, opened) }
    }

    @Test fun `source with types shows type headings and a visible group`() {
        open(SourceKey.HITOMI, listOf(FacetedSearchScope(SearchFacet.SERIES)))
        compose.onNodeWithText("Artists").assertExists()
        compose.onNodeWithText("Characters").assertExists()
        compose.onNodeWithText("+ or").assertExists()
        compose.onNodeWithText("Required").assertDoesNotExist()
    }

    @Test fun `source without types shows one list`() {
        open(SourceKey.GELBOORU, emptyList())
        compose.onNodeWithText("Required").assertExists()
        compose.onNodeWithText("Artists").assertDoesNotExist()
    }

    @Test fun `tapping an excluded tag selects it instead of removing it`() {
        open(SourceKey.GELBOORU, emptyList())
        compose.onNodeWithText("− sketch").performClick()
        compose.runOnIdle { assertTrue(actions.none { it is SearchAction.RemoveExcludeTerm }) }
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(SearchAction.RemoveExcludeTerm(sketch), actions.last()) }
    }

    @Test fun `add mode stays selected and any of offers the existing groups`() {
        open(SourceKey.GELBOORU, emptyList())
        compose.onNodeWithTag("Search composer").assertExists()
        compose.onNodeWithText("Any of").performClick()
        compose.onNodeWithText("rin or aoi").assertExists()
        compose.onNodeWithText("New group").assertExists()
    }
}
