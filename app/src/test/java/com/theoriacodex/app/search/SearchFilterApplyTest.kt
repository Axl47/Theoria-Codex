package com.theoriacodex.app.search

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchFilterApplyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `changing filters searches once when the sheet closes`() {
        val state = mutableStateOf(SearchUiState())
        val actions = mutableListOf<SearchAction>()
        compose.setContent {
            MaterialTheme {
                SearchScreen(
                    state = state.value, creatorBrowsingSources = emptySet(),
                    onAction = { action ->
                        actions += action
                        if (action is SearchAction.SelectSort) {
                            state.value = state.value.copy(
                                query = state.value.query.copy(draft = state.value.query.draft.copy(sort = action.sort)),
                            )
                        }
                    },
                    resolvePostById = { null }, recoverPostMedia = { _, _ -> null },
                    tagVideoCountProvider = { _, _ -> null }, fetchTagVideoCounts = { _, _ -> emptyMap() },
                    onOpenCreatorProfile = {}, onOpenLegacyCreatorProfile = {},
                    onRequestSaveToCodex = {}, onSaveToDevice = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Filter and sort").performClick()
        compose.onNodeWithText("Popular").performClick()
        compose.onNodeWithText("Top").performClick()
        compose.runOnIdle { assertEquals(0, actions.count { it == SearchAction.ApplyDraft }) }
        compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1, actions.count { it == SearchAction.ApplyDraft }) }
    }
}
