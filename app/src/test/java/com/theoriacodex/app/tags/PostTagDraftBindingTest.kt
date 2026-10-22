package com.theoriacodex.app.tags

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostTagDraftBindingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `switching polarity removes the opposing canonical selection only after acceptance`() {
        val tag = SearchTerm("cat")
        val base = SearchUiState().query.draft
        val draft = mutableStateOf(base.copy(excludeTerms = listOf(tag)))
        var accept = true
        compose.setContent { MaterialTheme {
            PostTagActionSection(testPost().copy(canonicalTags = listOf("cat"), taxonomy = emptyList()),
                draftQuery = draft.value,
                onAddIncludeTerm = { term ->
                    if (accept) draft.value = draft.value.withIncludeTermsAsRequired(draft.value.includeTerms + term)
                    accept
                },
                onAddExcludeTerm = { term ->
                    if (accept) draft.value = draft.value.copy(excludeTerms = draft.value.excludeTerms + term)
                    accept
                },
                onRemoveIncludeTerm = { term -> draft.value = draft.value.withIncludeTermsAsRequired(draft.value.includeTerms - term) },
                onRemoveExcludeTerm = { term -> draft.value = draft.value.copy(excludeTerms = draft.value.excludeTerms - term) })
        } }
        fun click(label: String) = compose.onNodeWithContentDescription(label)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        val absent = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        click("Include cat in Search")
        compose.onNodeWithText("+").assert(selected)
        compose.onNodeWithText("-").assert(absent)
        compose.runOnIdle { accept = false }
        click("Exclude cat from Search")
        compose.onNodeWithText("+").assert(selected)
        compose.onNodeWithText("-").assert(absent)
        compose.runOnIdle { accept = true }
        click("Exclude cat from Search")
        compose.onNodeWithText("+").assert(absent)
        compose.onNodeWithText("-").assert(selected)
        compose.runOnIdle { accept = false }
        click("Include cat in Search")
        compose.onNodeWithText("+").assert(absent)
        compose.onNodeWithText("-").assert(selected)
        assertEquals(listOf(tag), draft.value.excludeTerms)
    }

    @Test fun `reopening and external edits follow the draft while queued acceptance never invents selection`() {
        val tag = SearchTerm("cat")
        val base = SearchUiState()
        val owner = MutableStateFlow(base.copy(query = base.query.copy(draft = base.query.draft
            .withIncludeTermGroups(listOf(SearchTermGroup(listOf(tag, SearchTerm("dog"))))))))
        val visible = mutableStateOf(true)
        val removed = mutableListOf<SearchTerm>()
        var added = 0
        val post = testPost().copy(canonicalTags = listOf("cat"), taxonomy = emptyList())
        compose.setContent {
            MaterialTheme { PostTagContextProvider(owner) {
                if (visible.value) PostTagActionSection(post,
                    onAddIncludeTerm = { added++; true }, onAddExcludeTerm = { true },
                    onRemoveIncludeTerm = { removed += it }, onRemoveExcludeTerm = {})
            } }
        }
        val included = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        val absent = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        compose.onNodeWithText("+").assert(included)
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("+").assert(included).performClick()
        assertEquals(listOf(tag), removed)
        compose.runOnIdle { owner.value = base }
        compose.onNodeWithText("+").assert(absent).performClick()
        assertEquals(1, added)
        compose.onNodeWithText("+").assert(absent)
    }
}
