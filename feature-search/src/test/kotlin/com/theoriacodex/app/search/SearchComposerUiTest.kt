package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.search.omnibox.OmniboxKind
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.theoriacodex.domain.model.SortMode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h900dp")
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
        omnibox: List<OmniboxEntry> = emptyList(),
        onDismiss: () -> Unit = {},
        savedSearches: (SearchUiState) -> ComposerSavedSearches = { ComposerSavedSearches(saved, onOpen = onOpenSaved) },
    ) {
        val base = SearchUiState()
        val draft = emptySearchQuery(QueryMode.Source(source)).copy(
            includeTermGroups = include,
            excludeTerms = listOf(sketch),
        )
        val query = base.query.copy(draft = draft, applied = draft, appliedQueryHash = "applied", scopesBySource = mapOf(source to scopes))
        val state = base.copy(query = query, suggestions = suggestions)
        compose.setContent {
            MaterialTheme {
                SearchComposer(
                    state = state, onAction = { actions += it }, sourcePicker = {}, onApply = {}, onReset = {},
                    onUndo = {}, onManageSavedTags = onManageSavedTags, onOpenSourceAccounts = {}, onDismiss = onDismiss,
                    savedSearches = savedSearches(state), savedTags = savedTags, omnibox = omnibox,
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
        compose.onNodeWithText("My landscapes").performClick()
        compose.runOnIdle { assertEquals(entry, opened) }
    }

    private fun editing(state: SearchUiState, id: String?) = state.copy(editingSavedSearchId = id)

    @Test fun `editing a saved search swaps Save for Update, which hands over the search on screen (ADR 0218)`() {
        val evening = SavedSearchEntry("evening", "Evening", RecentSearchEntry(emptySearchQuery(), "old", 1L), 1L)
        var updated: Pair<SavedSearchEntry, RecentSearchEntry>? = null
        var saves = 0
        open(SourceKey.GELBOORU, emptyList(), savedSearches = { state ->
            composerSavedSearches(editing(state, "evening"), listOf(evening), { _, _ -> saves++ }, { saved, search -> updated = saved to search }, {}, {})
        })
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Update “Evening”").performClick()
        compose.runOnIdle {
            assertEquals(evening, updated?.first)
            assertEquals("applied", updated?.second?.queryHash)
            assertEquals(groups, updated?.second?.query?.includeTermGroups)
            assertEquals(0, saves)
        }
    }

    @Test fun `Update waits for the edit to run and change the search, and names the saved search it would duplicate (ADR 0218)`() {
        val state = SearchUiState().let { base ->
            val draft = emptySearchQuery(QueryMode.Source(SourceKey.GELBOORU)).copy(includeTermGroups = groups)
            val scope = SearchSourceScope.fromQuery(draft)
            base.copy(query = base.query.copy(draft = draft, applied = draft, appliedQueryHash = "applied",
                draftSourceScope = scope, appliedSourceScope = scope))
        }
        var onScreen: RecentSearchEntry? = null
        val save = composerSavedSearches(state, emptyList(), { _, search -> onScreen = search }, { _, _ -> }, {}, {}).action
        (save as ComposerSavedSearchAction.Save).onSave("x")
        val shown = checkNotNull(onScreen)
        val evening = SavedSearchEntry("evening", "Evening", RecentSearchEntry(emptySearchQuery(), "old", 1L), 1L)
        val morning = SavedSearchEntry("morning", "Morning", shown.copy(queryHash = "older"), 1L)
        fun update(state: SearchUiState, edited: SavedSearchEntry, entries: List<SavedSearchEntry>) =
            composerSavedSearches(editing(state, edited.id), entries, { _, _ -> }, { _, _ -> }, {}, {}).action
                as ComposerSavedSearchAction.Update

        assertEquals(true, update(state, evening, listOf(evening)).onUpdate != null)
        val unchanged = evening.copy(search = shown.copy(queryHash = "older"))
        assertEquals(null, update(state, unchanged, listOf(unchanged)).onUpdate)
        val pending = state.copy(query = state.query.copy(draft = state.query.draft.withIncludeTermsAsRequired(emptyList())))
        assertEquals(null, update(pending, evening, listOf(evening)).onUpdate)
        val untagged = state.copy(query = state.query.copy(
            draft = state.query.draft.withIncludeTermsAsRequired(emptyList()),
            applied = state.query.applied.withIncludeTermsAsRequired(emptyList()),
        ))
        assertEquals(true, update(untagged, evening, listOf(evening)).onUpdate != null)
        val duplicate = update(state, evening, listOf(evening, morning))
        assertEquals("Morning", duplicate.conflict)
        assertEquals(null, duplicate.onUpdate)
    }

    @Test fun `a duplicate shows why Update is off (ADR 0218)`() {
        open(SourceKey.GELBOORU, emptyList(), savedSearches = {
            ComposerSavedSearches(action = ComposerSavedSearchAction.Update("Evening", conflict = "Morning"))
        })
        compose.onNodeWithText("Update “Evening”").assertIsNotEnabled()
        compose.onNodeWithText("“Morning” already saves this search.").assertExists()
    }

    @Test fun `without a saved search being edited the composer offers Save, not Update (ADR 0218)`() {
        open(SourceKey.GELBOORU, emptyList(), savedSearches = { state ->
            composerSavedSearches(state, emptyList(), { _, _ -> }, { _, _ -> }, {}, {})
        })
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Update", substring = true).assertDoesNotExist()
    }

    @Test fun `the edit ends when the composer closes after opening, not before it opens (ADR 0218)`() {
        var open by mutableStateOf(false)
        var editingId by mutableStateOf<String?>(null)
        var ended = 0
        compose.setContent { SavedSearchEditEndEffect(editingId, { open }) { ended++ } }
        compose.runOnIdle { editingId = "evening" }
        compose.runOnIdle { assertEquals(0, ended); open = true }
        compose.runOnIdle { assertEquals(0, ended); open = false }
        compose.runOnIdle { assertEquals(1, ended) }
    }

    @Test fun `the composer menu opens Filter and sort (ADR 0218)`() {
        var opened = 0
        val state = SearchUiState()
        compose.setContent {
            MaterialTheme {
                SearchComposer(
                    state = state, onAction = {}, sourcePicker = {}, onApply = {}, onReset = {}, onUndo = {},
                    onManageSavedTags = {}, onOpenSourceAccounts = {}, onDismiss = {}, onOpenFilters = { opened++ },
                )
            }
        }
        compose.onNodeWithContentDescription("More search actions").performClick()
        compose.onNodeWithText("Filter and sort").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun `a saved search chip names its sort only when it is not Newest`() {
        val newest = SavedSearchEntry("newest", "Landscapes", RecentSearchEntry(emptySearchQuery(), "a", 1L), 1L)
        val top = SavedSearchEntry("top", "Gel faves", RecentSearchEntry(emptySearchQuery().copy(sort = SortMode.TOP), "b", 1L), 1L)
        open(SourceKey.GELBOORU, emptyList(), listOf(newest, top))
        compose.onNodeWithText("Top").assertExists()
        compose.onAllNodesWithText("Newest").assertCountEquals(0)
    }

    @Test fun `saved tags cycle through the search and can be managed`() {
        var managed = false
        open(
            SourceKey.GELBOORU, emptyList(), onManageSavedTags = { managed = true },
            savedTags = ComposerSavedTags(listOf(FavoriteTagEntry(SourceKey.GELBOORU, "blue_hair"), FavoriteTagEntry(SourceKey.GELBOORU, "sketch"))),
        )
        compose.onNodeWithText("Saved tags").assertExists()
        val label = compose.onNodeWithText("blue_hair", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("The scrolling row must leave the label room", label.right > label.left)
        compose.onNodeWithText("blue_hair").performClick()
        compose.runOnIdle { assertEquals(SearchAction.AddIncludeTerm(SearchTerm("blue_hair")), actions.last()) }
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

    @Config(qualifiers = "w320dp-h470dp")
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

    @Test fun `typed text also reaches codices and commands, below the tags`() {
        var opened = false
        var dismissed = false
        var instantSeek = false
        open(
            SourceKey.GELBOORU, emptyList(),
            suggestions = SearchUiState().suggestions.copy(
                input = "ins",
                autocomplete = listOf(TagSuggestion("insect", type = null, count = null)),
            ),
            omnibox = listOf(
                OmniboxEntry.Action(OmniboxKind.CODEX, "Insects", "12 posts", cover = emptyList()) { opened = true },
                OmniboxEntry.Toggle("Instant Seek", checked = false) { instantSeek = it },
                OmniboxEntry.Action(OmniboxKind.CODEX, "Landscapes") {},
            ),
            onDismiss = { dismissed = true },
        )
        compose.onNodeWithText("Landscapes").assertDoesNotExist()
        compose.onNodeWithText("12 posts").assertIsDisplayed()
        compose.onNodeWithContentDescription("Codex").assertIsDisplayed()
        val tag = compose.onNodeWithText("insect").getUnclippedBoundsInRoot().top
        assertTrue(tag < compose.onNodeWithText("Insects").getUnclippedBoundsInRoot().top)

        compose.onNodeWithText("Instant Seek").performClick()
        assertTrue(instantSeek)
        assertFalse(dismissed)
        compose.onNodeWithText("Insects").performClick()
        assertTrue(opened)
        assertTrue(dismissed)
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
