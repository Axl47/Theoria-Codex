package com.theoriacodex.app.search

import com.theoriacodex.app.related.testRelatedShelf
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.SearchHistoryEntry
import com.theoriacodex.app.search.state.SearchQueryHistory
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.appliedHistoryEntry
import com.theoriacodex.app.search.state.emptySearchQuery
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSuggestionsUiState
import com.theoriacodex.app.search.state.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchInputSubmissionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `Undo last search dispatches only when there is a settled search to return to`() {
        val state = mutableStateOf(SearchUiState())
        val actions = mutableListOf<SearchAction>()
        compose.setContent { MaterialTheme { Box(Modifier.size(400.dp, 700.dp)) {
            SearchScreen(
                state = state.value, related = testRelatedShelf(), creatorBrowsingSources = emptySet(), onAction = { actions += it },
                resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                onRequestSaveToCodex = {}, onSaveToDevice = {},
            )
        } } }
        compose.onNodeWithTag("Search bar").performClick()
        compose.onNodeWithContentDescription("More search actions").performClick()
        val undo = compose.onNodeWithText("Undo last search")
        undo.assertIsNotEnabled()
        compose.runOnIdle {
            val earlier = SearchHistoryEntry(emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm("a"))), SearchSourceScope.GlobalUnified)
            state.value = state.value.copy(history = SearchQueryHistory(back = listOf(earlier), current = state.value.appliedHistoryEntry()))
        }
        undo.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.count { it == SearchAction.UndoSearch }) }
    }

    @Test
    fun `IME adds the typed tag and keeps the editor open, but blocked input stays`() {
        val state = mutableStateOf(SearchUiState())
        val submitted = mutableListOf<String>()
        val actions = mutableListOf<SearchAction>()
        val focusRefreshInputs = mutableListOf<String>()
        setSubmissionContent(state, submitted, actions, focusRefreshInputs)
        compose.onNodeWithTag("Search bar").performClick()
        val input = compose.onNodeWithTag("Search query input")
        input.performClick()
        input.performTextInput("old-input")
        compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
        input.performImeAction()
        compose.runOnIdle {
            assertTrue(submitted.isEmpty())
            assertFalse(SearchAction.ApplyDraft in actions)
        }

        input.performTextReplacement("landscape")
        compose.runOnIdle {
            state.value = state.value.copy(suggestions = state.value.suggestions.copy(canCommitInput = true))
        }
        compose.onNodeWithText("Add", substring = false).assertIsEnabled()
        input.performImeAction()

        compose.runOnIdle {
            assertEquals(listOf("landscape"), submitted)
            assertFalse("actions=$actions", SearchAction.ApplyDraft in actions)
            assertTrue("Focus refresh must not resurrect submitted input: $focusRefreshInputs", focusRefreshInputs.all(String::isEmpty))
        }
        compose.onNodeWithTag("Search composer").assertExists()
    }

    @Test
    fun `IME with nothing typed runs the draft and closes`() {
        val state = mutableStateOf(SearchUiState())
        val actions = mutableListOf<SearchAction>()
        setSubmissionContent(state, mutableListOf(), actions, mutableListOf())
        compose.onNodeWithTag("Search bar").performClick()
        compose.onNodeWithTag("Search query input").performImeAction()
        compose.runOnIdle { assertTrue("actions=$actions", SearchAction.ApplyDraft in actions) }
        compose.onNodeWithTag("Search composer").assertDoesNotExist()
    }

    private fun setSubmissionContent(
        state: androidx.compose.runtime.MutableState<SearchUiState>,
        submitted: MutableList<String>,
        actions: MutableList<SearchAction>,
        focusRefreshInputs: MutableList<String>,
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    SearchScreen(
                        state = state.value,
                        related = testRelatedShelf(),
                        creatorBrowsingSources = emptySet(),
                        onAction = { action ->
                            actions += action
                            when (action) {
                                SearchAction.RefreshAutocomplete -> {
                                    focusRefreshInputs += state.value.suggestions.input
                                }
                                is SearchAction.AutocompleteChanged -> state.value = state.value.copy(
                                    suggestions = state.value.suggestions.copy(input = action.input, canCommitInput = false),
                                )
                                is SearchAction.CommitTagInput -> {
                                    submitted += action.input
                                    state.value = state.value.copy(suggestions = SearchSuggestionsUiState())
                                }
                                SearchAction.ClearAutocomplete -> state.value = state.value.copy(
                                    suggestions = SearchSuggestionsUiState(),
                                )
                                else -> Unit
                            }
                        },
                        resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                        onRequestSaveToCodex = {}, onSaveToDevice = {},
                    )
                }
            }
        }
    }

}
