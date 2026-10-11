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
@Config(application = Application::class, sdk = [35])
class PostTagDraftBindingTest {
    @get:Rule val compose = createComposeRule()

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
            MaterialTheme { SearchDraftSelectionProvider(owner) {
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
