package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInput
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSuggestionsUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.FavoriteTagEntry
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

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun open(
        source: SourceKey,
        scopes: List<FacetedSearchScope>,
        saved: List<SavedSearchEntry> = emptyList(),
        onOpenSaved: (SavedSearchEntry) -> Unit = {},
        suggestions: SearchSuggestionsUiState = SearchUiState().suggestions,
        onManageSavedTags: () -> Unit = {},
        savedTags: ComposerSavedTags = ComposerSavedTags(emptyList()),
        include: List<SearchTermGroup> = groups,
    ) {
        val base = SearchUiState()
        val draft = emptySearchQuery(QueryMode.Source(source)).copy(
            includeTermGroups = include,
            excludeTerms = listOf(sketch),
        )
        val state = base.copy(query = base.query.copy(draft = draft, supportedScopes = scopes), suggestions = suggestions)
        compose.setContent {
            MaterialTheme {
                SearchComposer(
                    state = state, onAction = { actions += it }, sourcePicker = {}, onApply = {}, onReset = {},
                    onUndo = {}, onManageSavedTags = onManageSavedTags, onOpenSourceAccounts = {}, onDigitsCommitted = {}, onDismiss = {},
                    savedSearches = ComposerSavedSearches(saved, onOpen = onOpenSaved), savedTags = savedTags,
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
        compose.onNodeWithText("Saved searches").assertExists()
        compose.onNodeWithText("Tap to run").assertExists()
        compose.onNodeWithText("My landscapes").performClick()
        compose.runOnIdle { assertEquals(entry, opened) }
    }

    @Test fun `saved tags cycle through the search and can be managed`() {
        var managed = false
        open(
            SourceKey.GELBOORU, emptyList(), onManageSavedTags = { managed = true },
            savedTags = ComposerSavedTags(listOf(FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair"), FavoriteTagEntry(SourceKey.GELBOORU, "sketch"))),
        )
        compose.onNodeWithText("Saved tags").assertExists()
        compose.onNodeWithText("Tap to add to your search").assertExists()
        val label = compose.onNodeWithText("blue_hair", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("The scrolling row must leave the label room", label.right > label.left)
        compose.onNodeWithText("blue_hair").performClick()
        compose.runOnIdle { assertEquals(SearchAction.AddIncludeTerm(SearchTerm("blue_hair")), actions.last()) }
        // Already excluded in the fixture's draft, so the chip shows it and its tap clears it.
        compose.onNode(hasStateDescription("Excluded")).performClick()
        compose.runOnIdle { assertEquals(SearchAction.RemoveExcludeTerm(sketch), actions.last()) }
        compose.onNodeWithText("Manage").performClick()
        compose.runOnIdle { assertTrue(managed) }
    }

    @Test fun `tapping a general tag offers to save it and a saved group adds as one group`() {
        var savedAs: Pair<SourceKey, List<String>>? = null
        val group = FavoriteTagEntry(SourceKey.GELBOORU, listOf("blue_hair", "red_hair"))
        open(
            SourceKey.GELBOORU, emptyList(),
            savedTags = ComposerSavedTags(listOf(group), SourceKey.GELBOORU, onSave = { source, tags, _ -> savedAs = source to tags }),
        )
        compose.onNodeWithText("Artist mika").performClick()
        compose.onNodeWithText("Save tag").assertDoesNotExist()
        compose.onNodeWithText("Exclude instead").assertExists()
        compose.onNodeWithText("− sketch").performClick()
        compose.onNodeWithText("Save tag").performClick()
        compose.runOnIdle { assertEquals(SourceKey.GELBOORU to listOf("sketch"), savedAs) }
        compose.onNodeWithText("blue_hair or red_hair").performClick()
        compose.runOnIdle { assertEquals(SearchAction.AddSavedTagGroup(group.terms), actions.last()) }
    }

    @Test fun `saving an or group asks for an optional name and the chip shows it`() {
        var saved: Triple<SourceKey, List<String>, String?>? = null
        val sea = SearchTermGroup(listOf(SearchTerm("sea"), SearchTerm("sky")))
        open(
            SourceKey.GELBOORU, emptyList(), include = listOf(sea),
            savedTags = ComposerSavedTags(
                listOf(FavoriteTagEntry(SourceKey.GELBOORU, listOf("cat", "dog"), "Pets")), SourceKey.GELBOORU,
                onSave = { source, tags, name -> saved = Triple(source, tags, name) },
            ),
        )
        compose.onNodeWithText("Pets").assertExists()
        compose.onNodeWithText("sky").performClick()
        compose.onNodeWithText("Save the “or” group").performClick()
        compose.onNodeWithText("Name (optional)").performTextInput("Outdoors")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(Triple(SourceKey.GELBOORU, listOf("sea", "sky"), "Outdoors"), saved) }

        compose.onNodeWithText("sea").performClick()
        compose.onNodeWithText("Save the “or” group").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(null, saved?.third) }
    }

    @Test fun `a saved tag matching the typed text leads the suggestions with a star`() {
        open(
            SourceKey.GELBOORU, emptyList(),
            savedTags = ComposerSavedTags(listOf(FavoriteTagEntry(SourceKey.GELBOORU, "riverside"))),
            suggestions = SearchUiState().suggestions.copy(
                input = "river",
                autocomplete = listOf(TagSuggestion("rivers", type = null, count = null)),
            ),
        )
        compose.onNodeWithContentDescription("Saved tag").assertIsDisplayed()
        val saved = compose.onNodeWithText("riverside").getUnclippedBoundsInRoot().top
        val provider = compose.onNodeWithText("rivers").getUnclippedBoundsInRoot().top
        assertTrue(saved < provider)
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
        open(SourceKey.GELBOORU, emptyList())
        compose.onNodeWithText("Undo last search").assertDoesNotExist()
        compose.onNodeWithContentDescription("More search actions").performClick()
        compose.onNodeWithText("Undo last search").assertIsNotEnabled()
    }
}
