package com.theoriacodex.app.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchSourceChipDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun temporaryScopeChipsExposeIndependentSelectionAndOneCombinedOwner() {
        var scope by mutableStateOf(
            SearchSourceScope.fromSources(listOf(SourceKey.PIXIV, SourceKey.GELBOORU)),
        )
        val clicked = mutableListOf<QueryMode>()
        val toggled = mutableListOf<SourceKey>()

        composeRule.setContent {
            MaterialTheme {
                ModeRow(
                    draftSourceScope = scope,
                    options = listOf(
                        QueryMode.Unified,
                        QueryMode.Source(SourceKey.PIXIV),
                        QueryMode.Source(SourceKey.GELBOORU),
                    ),
                    unifiedSourceCount = 2,
                    onModeSelected = { clicked += it },
                    onTemporarySourceToggled = { source ->
                        toggled += source
                        scope = SearchSourceScope.fromSources(scope.explicitSources - source)
                    },
                )
            }
        }

        val unified = composeRule.onNodeWithContentDescription("Unified")
        val pixiv = composeRule.onNodeWithContentDescription("Pixiv")
        val gelbooru = composeRule.onNodeWithContentDescription("Gelbooru")
        pixiv.assertIsSelected()
        gelbooru.assertIsSelected()
        unified.assertIsNotSelected()
        assertCombinedOwner(pixiv, hasLongClick = true)
        assertCombinedOwner(unified, hasLongClick = false)

        pixiv.performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        gelbooru.performTouchInput {
            down(center)
            advanceEventTime(1_000)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf(QueryMode.Source(SourceKey.PIXIV)), clicked)
            assertEquals(listOf(SourceKey.GELBOORU), toggled)
        }
        pixiv.assertIsSelected()
        gelbooru.assertIsNotSelected()
        unified.assertIsNotSelected()
    }

    private fun assertCombinedOwner(node: SemanticsNodeInteraction, hasLongClick: Boolean) {
        val config = node.fetchSemanticsNode().config
        assertEquals(Role.Checkbox, config[SemanticsProperties.Role])
        assertTrue(config.contains(SemanticsActions.OnClick))
        if (hasLongClick) {
            assertTrue(config.contains(SemanticsActions.OnLongClick))
        } else {
            assertFalse(config.contains(SemanticsActions.OnLongClick))
        }
    }
}
