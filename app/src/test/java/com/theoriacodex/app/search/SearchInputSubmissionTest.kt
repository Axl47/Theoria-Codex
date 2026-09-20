package com.theoriacodex.app.search

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSuggestionsUiState
import com.theoriacodex.app.search.state.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [27, 35])
class SearchInputSubmissionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `IME submits current input after eligibility changes and rejects earlier empty or blocked input`() {
        val state = mutableStateOf(SearchUiState())
        val submitted = mutableListOf<String>()
        val actions = mutableListOf<SearchAction>()
        val focusRefreshInputs = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(400.dp, 700.dp)) {
                    SearchScreen(
                        state = state.value,
                        creatorBrowsingSources = emptySet(),
                        onAction = { action ->
                            actions += action
                            when (action) {
                                SearchAction.RefreshAutocomplete -> {
                                    // Focus refresh reads the owner's current input, never a captured render value.
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
                        tagVideoCountProvider = { _, _ -> null }, fetchTagVideoCounts = { _, _ -> emptyMap() },
                        onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {},
                        onRequestSaveToCodex = {}, onSaveToDevice = {},
                    )
                }
            }
        }
        val input = compose.onNodeWithTag("Search query input")
        input.performClick()
        input.performImeAction()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }

        input.performTextInput("old-input")
        compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
        input.performImeAction()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }

        input.performTextReplacement("landscape")
        compose.onNodeWithText("Add", substring = false).assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(suggestions = state.value.suggestions.copy(canCommitInput = true))
        }
        compose.onNodeWithText("Add", substring = false).assertIsEnabled()
        input.performImeAction()

        compose.runOnIdle {
            assertEquals(listOf("landscape"), submitted)
            assertEquals("Owner input after submission; actions=$actions", "", state.value.suggestions.input)
        }
        try {
            compose.waitUntil(2_000) {
                input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text.isEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError(
                "Owner input='${state.value.suggestions.input}', " +
                    "rendered input='${input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text}', " +
                    "actions=$actions",
                failure,
            )
        }
        compose.runOnIdle {
            assertEquals("Owner input after rendered reset; actions=$actions", "", state.value.suggestions.input)
            assertTrue("Focus refresh must not resurrect submitted input: $focusRefreshInputs", focusRefreshInputs.all(String::isEmpty))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            input.assertIsNotFocused()
            compose.onRoot().performKeyInput { pressKey(Key.Tab) }
            input.assertIsFocused()
            compose.runOnIdle {
                assertEquals("Keyboard re-entry must retain the cleared input", "", state.value.suggestions.input)
                assertEquals(listOf("landscape"), submitted)
            }
        }
    }
}
